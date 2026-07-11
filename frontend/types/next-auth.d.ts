import 'next-auth';
import 'next-auth/jwt';

declare module 'next-auth' {
	interface Session {
		// accessToken은 세션에 싣지 않는다 (R-8). /api/auth/session으로 노출되기 때문이다.
		// 토큰은 JWT에만 두고 BFF가 서버측에서 읽는다 (lib/core bearerToken).
		/** 갱신에 실패했다. 만료된 베어러를 코어로 보내지 않고 다시 로그인시킨다 (S25). */
		error?: 'RefreshAccessTokenError';
	}

	interface User {
		/** 앱 역할. 자기 역할이라 세션에 노출한다 — UI가 governed retrieval을 드러내는 데 쓴다. */
		role?: 'USER' | 'ADMIN';
	}
}

declare module 'next-auth/jwt' {
	interface JWT {
		accessToken?: string;
		refreshToken?: string;
		/** epoch seconds. */
		expiresAt?: number;
		error?: 'RefreshAccessTokenError';
		role?: 'USER' | 'ADMIN';
	}
}
