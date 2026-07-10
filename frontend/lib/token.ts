/**
 * Keycloak access token 갱신 (S25, SEC-1 "Refresh는 Keycloak 위임").
 *
 * access token은 5분, refresh token은 30분이다. 갱신이 없으면 사용자는 5분마다 재로그인해야 한다.
 *
 * 순수 로직으로 둔다. `auth.ts`는 next-auth를 끌고 오므로 테스트에서 켤 수 없다.
 */

export type TokenSet = {
	accessToken: string;
	refreshToken?: string;
	/** epoch seconds. */
	expiresAt: number;
	error?: 'RefreshAccessTokenError';
};

export type KeycloakConfig = {
	issuer: string;
	clientId: string;
	clientSecret: string;
};

/**
 * 만료 판정에 두는 여유. 갱신 요청이 날아가는 사이에 만료되면 코어가 401을 준다.
 */
export const EXPIRY_SKEW_SECONDS = 30;

export function isExpired(
	token: { expiresAt?: number },
	nowSeconds: number,
	skewSeconds: number = EXPIRY_SKEW_SECONDS,
): boolean {
	if (typeof token.expiresAt !== 'number') return true;
	return nowSeconds >= token.expiresAt - skewSeconds;
}

/**
 * 갱신은 **던지지 않는다.** 실패는 `error` 필드로 표시한다 — jwt 콜백에서 예외가 나면
 * Auth.js의 동작이 예측하기 어려워진다. 호출자는 `error`를 보고 재로그인을 유도한다.
 */
export async function refreshAccessToken(
	token: TokenSet,
	config: KeycloakConfig,
	fetchImpl: typeof fetch = fetch,
	nowSeconds: () => number = () => Math.floor(Date.now() / 1000),
): Promise<TokenSet> {
	if (!token.refreshToken) {
		return { ...token, error: 'RefreshAccessTokenError' };
	}

	try {
		const response = await fetchImpl(`${config.issuer}/protocol/openid-connect/token`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
			body: new URLSearchParams({
				grant_type: 'refresh_token',
				refresh_token: token.refreshToken,
				client_id: config.clientId,
				client_secret: config.clientSecret,
			}).toString(),
		});

		if (!response.ok) {
			// refresh token도 만료됐다(30분). 재로그인 외에 방법이 없다.
			return { ...token, error: 'RefreshAccessTokenError' };
		}

		const refreshed = (await response.json()) as {
			access_token: string;
			expires_in: number;
			refresh_token?: string;
		};

		return {
			accessToken: refreshed.access_token,
			// Keycloak은 갱신 때 새 refresh token을 준다(회전). 옛 값을 계속 쓰면 다음 갱신에서 끊긴다.
			refreshToken: refreshed.refresh_token ?? token.refreshToken,
			expiresAt: nowSeconds() + refreshed.expires_in,
		};
	} catch {
		return { ...token, error: 'RefreshAccessTokenError' };
	}
}
