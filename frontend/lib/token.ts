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
 * BFF가 이 토큰으로 코어를 불러도 되는가.
 *
 * access token이 있어야 하고, 갱신 실패(`error`)가 없어야 한다. <b>둘 다 봐야 한다.</b> 갱신에 실패한 세션은
 * 만료된 베어러를 들고 있으므로, 그것을 상류로 보내면 코어가 401을 줄 뿐이고 사용자는 원인을 알 수 없다 (S25).
 * proxyToCore와 채팅 스트림 라우트가 공유해, error 가드를 지우면 이 판정 테스트가 잡는다.
 */
export function hasUsableToken(token: { accessToken?: string; error?: string }): boolean {
	return Boolean(token.accessToken) && !token.error;
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
			// 반드시 명시한다. 호출자(auth.ts)가 `{...token, ...refreshed}`로 병합하므로,
			// 이 키가 없으면 이전 실패의 error가 살아남아 갱신에 성공해도 재로그인 화면에 갇힌다.
			error: undefined,
		};
	} catch {
		return { ...token, error: 'RefreshAccessTokenError' };
	}
}
