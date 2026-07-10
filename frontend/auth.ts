import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';

/**
 * Keycloak OIDC Authorization Code Flow (S25).
 *
 * 비밀번호·MFA는 Keycloak에 위임한다. 프론트는 access token을 세션에 실어 두었다가
 * BFF가 코어를 부를 때 Authorization 헤더로 바꾼다 (docs/01).
 */
export const { handlers, auth, signIn, signOut } = NextAuth({
	providers: [
		Keycloak({
			clientId: process.env.KEYCLOAK_CLIENT_ID!,
			clientSecret: process.env.KEYCLOAK_CLIENT_SECRET!,
			issuer: `${process.env.KEYCLOAK_URL}/realms/${process.env.KEYCLOAK_REALM}`,
		}),
	],
	callbacks: {
		jwt({ token, account }) {
			// 로그인 직후에만 account가 있다. 이후 요청에서는 토큰에 남긴 값을 쓴다.
			if (account?.access_token) {
				token.accessToken = account.access_token;
			}
			return token;
		},
		session({ session, token }) {
			session.accessToken = token.accessToken as string;
			return session;
		},
	},
});
