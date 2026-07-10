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
const keycloak = {
	issuer: `${process.env.KEYCLOAK_URL}/realms/${process.env.KEYCLOAK_REALM}`,
	clientId: process.env.KEYCLOAK_CLIENT_ID!,
	clientSecret: process.env.KEYCLOAK_CLIENT_SECRET!,
};

export const { handlers, auth, signIn, signOut } = NextAuth({
	providers: [
		Keycloak({
			clientId: keycloak.clientId,
			clientSecret: keycloak.clientSecret,
			issuer: keycloak.issuer,
		}),
	],
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
				};
			}

			if (!isExpired(token, Math.floor(Date.now() / 1000))) {
				return token;
			}

			const refreshed = await refreshAccessToken(token as unknown as TokenSet, keycloak);
			return { ...token, ...refreshed };
		},
		session({ session, token }) {
			session.accessToken = token.accessToken;
			session.error = token.error;
			return session;
		},
	},
});
