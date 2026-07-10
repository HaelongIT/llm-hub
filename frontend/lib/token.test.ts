import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { EXPIRY_SKEW_SECONDS, isExpired, refreshAccessToken } from './token.ts';
import type { KeycloakConfig, TokenSet } from './token.ts';

/**
 * Keycloak access token은 5분, refresh token은 30분이다. 갱신이 없으면 사용자는 5분마다
 * 재로그인해야 한다 (S25, SEC-1 "Refresh는 Keycloak 위임").
 *
 * 실측: Keycloak은 갱신 때 **새 refresh_token을 준다(회전)**. 회전된 값을 저장하지 않으면
 * 30분 뒤가 아니라 다음 갱신에서 바로 끊긴다.
 */

const 설정: KeycloakConfig = {
	issuer: 'http://keycloak/realms/llmhub',
	clientId: 'llmhub-frontend',
	clientSecret: 'secret',
};

const 유효한_토큰: TokenSet = {
	accessToken: 'old-access',
	refreshToken: 'old-refresh',
	expiresAt: 1_000_000,
};

/** 요청을 기록하고 정해진 응답을 돌려준다. */
function 대역_fetch(response: Response) {
	const 호출: Array<{ url: string; init?: RequestInit }> = [];
	const impl = async (url: string | URL | Request, init?: RequestInit) => {
		호출.push({ url: String(url), init });
		return response;
	};
	return { impl: impl as unknown as typeof fetch, 호출 };
}

function json(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('만료 판정', () => {
	it('만료 시각을 지나면 만료다', () => {
		assert.equal(isExpired({ expiresAt: 1000 }, 1001), true);
	});

	it('여유(skew) 안쪽이면 미리 만료로 본다', () => {
		// 갱신 요청이 날아가는 사이에 만료되면 안 된다.
		assert.equal(isExpired({ expiresAt: 1000 }, 1000 - EXPIRY_SKEW_SECONDS + 1), true);
	});

	it('여유 밖이면 아직 유효하다', () => {
		assert.equal(isExpired({ expiresAt: 1000 }, 1000 - EXPIRY_SKEW_SECONDS - 1), false);
	});

	it('만료 시각을 모르면 만료로 본다', () => {
		assert.equal(isExpired({}, 0), true);
	});
});

describe('토큰 갱신', () => {
	it('grant_type=refresh_token으로 Keycloak 토큰 엔드포인트를 친다', async () => {
		const { impl, 호출 } = 대역_fetch(json({ access_token: 'new', expires_in: 300, refresh_token: 'r2' }));

		await refreshAccessToken(유효한_토큰, 설정, impl);

		assert.equal(호출.length, 1);
		assert.equal(호출[0].url, 'http://keycloak/realms/llmhub/protocol/openid-connect/token');
		assert.equal(호출[0].init?.method, 'POST');

		const body = new URLSearchParams(String(호출[0].init?.body));
		assert.equal(body.get('grant_type'), 'refresh_token');
		assert.equal(body.get('refresh_token'), 'old-refresh');
		assert.equal(body.get('client_id'), 'llmhub-frontend');
		assert.equal(body.get('client_secret'), 'secret');
	});

	it('회전된 refresh token을 저장한다', async () => {
		// Keycloak은 갱신 때 새 refresh_token을 준다(실측). 옛 값을 계속 쓰면 다음 갱신에서 끊긴다.
		const { impl } = 대역_fetch(json({ access_token: 'new-access', expires_in: 300, refresh_token: 'new-refresh' }));

		const 갱신됨 = await refreshAccessToken(유효한_토큰, 설정, impl, () => 1_000_000);

		assert.equal(갱신됨.accessToken, 'new-access');
		assert.equal(갱신됨.refreshToken, 'new-refresh');
		assert.equal(갱신됨.expiresAt, 1_000_300);
		assert.equal(갱신됨.error, undefined);
	});

	it('갱신에 성공하면 이전 실패 표시를 지운다', async () => {
		// 일시적 네트워크 오류로 error가 한 번 붙은 토큰. 다음 시도에서 갱신이 성공했다.
		// 성공 반환에 error 키가 없으면 auth.ts의 `{...token, ...refreshed}` 병합에서
		// 옛 error가 살아남아 사용자가 영구히 재로그인 화면에 갇힌다.
		const 실패했던_토큰: TokenSet = { ...유효한_토큰, error: 'RefreshAccessTokenError' };
		const { impl } = 대역_fetch(json({ access_token: 'new', expires_in: 300, refresh_token: 'r2' }));

		const 갱신됨 = await refreshAccessToken(실패했던_토큰, 설정, impl);

		assert.equal(갱신됨.error, undefined);
		assert.equal({ ...실패했던_토큰, ...갱신됨 }.error, undefined, 'auth.ts의 병합 뒤에도 지워져야 한다');
	});

	it('응답에 refresh token이 없으면 쓰던 것을 유지한다', async () => {
		const { impl } = 대역_fetch(json({ access_token: 'new-access', expires_in: 300 }));

		const 갱신됨 = await refreshAccessToken(유효한_토큰, 설정, impl, () => 0);

		assert.equal(갱신됨.refreshToken, 'old-refresh');
	});

	it('refresh token이 없으면 요청조차 하지 않고 실패로 표시한다', async () => {
		const { impl, 호출 } = 대역_fetch(json({ access_token: 'never' }));

		const 갱신됨 = await refreshAccessToken({ accessToken: 'a', expiresAt: 0 }, 설정, impl);

		assert.equal(호출.length, 0);
		assert.equal(갱신됨.error, 'RefreshAccessTokenError');
	});

	it('Keycloak이 거부하면 실패로 표시한다', async () => {
		// refresh token도 만료됐다(30분). 재로그인 외에 방법이 없다.
		const { impl } = 대역_fetch(json({ error: 'invalid_grant' }, 400));

		const 갱신됨 = await refreshAccessToken(유효한_토큰, 설정, impl);

		assert.equal(갱신됨.error, 'RefreshAccessTokenError');
	});

	it('네트워크가 죽어도 던지지 않고 실패로 표시한다', async () => {
		const 죽은_fetch = (async () => {
			throw new Error('ECONNREFUSED');
		}) as unknown as typeof fetch;

		const 갱신됨 = await refreshAccessToken(유효한_토큰, 설정, 죽은_fetch);

		assert.equal(갱신됨.error, 'RefreshAccessTokenError');
	});
});
