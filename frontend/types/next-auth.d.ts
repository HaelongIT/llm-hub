import 'next-auth';
import 'next-auth/jwt';

declare module 'next-auth' {
	interface Session {
		accessToken?: string;
		/** 갱신에 실패했다. 만료된 베어러를 코어로 보내지 않고 다시 로그인시킨다 (S25). */
		error?: 'RefreshAccessTokenError';
	}
}

declare module 'next-auth/jwt' {
	interface JWT {
		accessToken?: string;
		refreshToken?: string;
		/** epoch seconds. */
		expiresAt?: number;
		error?: 'RefreshAccessTokenError';
	}
}
