import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';

import { isExpired, refreshAccessToken } from '@/lib/token';
import type { TokenSet } from '@/lib/token';

/**
 * Keycloak OIDC Authorization Code Flow (S25).
 *
 * 비밀번호·MFA는 Keycloak에 위임한다. 프론트는 access token을 세션에 실어 두었다가
 * BFF가 코어를 부를 때 Authorization 헤더로 바꾼다 (docs/01).
 *
 * access token은 5분, refresh token은 30분이다. 갱신하지 않으면 5분 뒤부터 코어가 401을
 * 돌려주는데, Auth.js 세션 쿠키는 기본 30일이라 사용자는 "로그인된" 화면에서 아무것도 못
 * 하게 된다. 그래서 만료 직전에 갱신한다 (SEC-1 "Refresh는 Keycloak 위임").
 *
 * 갱신에 실패하면(refresh token도 만료) `session.error`가 선다. 그때는 만료된 베어러를
 * 코어로 보내지 않고 다시 로그인시킨다.
 */
const clientId = process.env.KEYCLOAK_CLIENT_ID!;
const clientSecret = process.env.KEYCLOAK_CLIENT_SECRET!;
const realm = process.env.KEYCLOAK_REALM;

// 브라우저가 보는 외부 주소. 토큰의 iss(=KC_HOSTNAME)이자 로그인 리다이렉트(authorization) 주소다.
const externalIssuer = `${process.env.KEYCLOAK_URL}/realms/${realm}`;
// 서버측 백채널(code→token 교환·refresh·jwks·userinfo)은 내부 주소로 친다. 운영 컨테이너 안에서 외부
// URL(예: localhost:8081)은 자기 자신이라 도달하지 못한다 (리뷰 B1). 미설정이면 외부와 같아 dev(호스트
// 실행)는 지금과 동일하게 동작한다 — 회귀 없음. 운영 compose가 keycloak:8080으로 덮는다.
const internalBase = process.env.KEYCLOAK_INTERNAL_URL || process.env.KEYCLOAK_URL;
const internalIssuer = `${internalBase}/realms/${realm}`;
const splitBackchannel = internalBase !== process.env.KEYCLOAK_URL;

// refresh는 서버측 백채널이므로 내부 issuer로 토큰 엔드포인트를 친다 (token.ts).
const backchannel = { issuer: internalIssuer, clientId, clientSecret };

// 백채널 분리 시: authorization(브라우저)만 외부, token·userinfo·jwks(서버측)는 내부로 명시한다.
// 모든 엔드포인트를 명시해 discovery(외부 well-known 서버측 호출, 컨테이너에서 도달 불가)를 건너뛴다.
// 미분리(dev)면 지금처럼 issuer만 주고 discovery에 맡긴다.
const keycloakProvider = splitBackchannel
	? Keycloak({
			clientId,
			clientSecret,
			issuer: externalIssuer,
			authorization: `${externalIssuer}/protocol/openid-connect/auth`,
			token: `${internalIssuer}/protocol/openid-connect/token`,
			userinfo: `${internalIssuer}/protocol/openid-connect/userinfo`,
			jwks_endpoint: `${internalIssuer}/protocol/openid-connect/certs`,
		})
	: Keycloak({ clientId, clientSecret, issuer: externalIssuer });

/**
 * access token에서 앱 역할(USER/ADMIN)만 뽑는다. UI가 "역할" 칩으로 governed retrieval을 드러내는 데
 * 쓴다 — 자기 역할이라 민감정보가 아니다. 태그로 옮기지 않는다(역할→태그 매핑은 백엔드 소유).
 */
function appRole(accessToken?: string): 'USER' | 'ADMIN' | undefined {
	if (!accessToken) return undefined;
	try {
		const [, payload] = accessToken.split('.');
		const claims = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
		const roles: string[] = claims?.realm_access?.roles ?? [];
		if (roles.includes('ADMIN')) return 'ADMIN';
		if (roles.includes('USER')) return 'USER';
	} catch {
		// 파싱 실패는 조용히 무시한다. 역할 칩은 부가 정보다.
	}
	return undefined;
}

export const { handlers, auth, signIn, signOut } = NextAuth({
	providers: [keycloakProvider],
	callbacks: {
		async jwt({ token, account }) {
			// 로그인 직후에만 account가 있다. refresh token과 만료 시각을 여기서 챙기지 않으면
			// 이후 요청에서는 되찾을 방법이 없다.
			if (account) {
				return {
					...token,
					accessToken: account.access_token,
					refreshToken: account.refresh_token,
					expiresAt: account.expires_at,
					role: appRole(account.access_token),
				};
			}

			if (!isExpired(token, Math.floor(Date.now() / 1000))) {
				return token;
			}

			const refreshed = await refreshAccessToken(token as unknown as TokenSet, backchannel);
			return { ...token, ...refreshed };
		},
		session({ session, token }) {
			// access token을 세션에 싣지 않는다 (R-8). 세션은 /api/auth/session으로 브라우저 JS에
			// 노출되므로, 토큰은 JWT 쿠키에만 두고 BFF가 서버측(lib/core bearerToken)에서만 꺼낸다.
			// error는 재로그인 판단에 필요하고 민감정보가 아니므로 노출한다.
			session.error = token.error;
			if (session.user) {
				session.user.role = token.role;
			}
			return session;
		},
	},
});
