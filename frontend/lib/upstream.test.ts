import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { relayFailure } from './upstream.ts';

/**
 * 코어의 상태 코드를 뭉개면 백엔드의 정확한 인가 응답이 무의미해진다.
 *
 * 코어는 남의 세션에 404(403이 아니다 — 403은 그 세션이 존재한다는 사실을 알려준다),
 * 너무 긴 질문에 400, 만료된 토큰에 401을 준다. BFF가 전부 502로 바꾸면 클라이언트는
 * 셋을 구분할 수 없고, 401을 못 보므로 재로그인 유도도 불가능하다.
 */
describe('코어 실패 응답 중계', () => {
	it('404를 그대로 넘긴다 — 남의 세션', async () => {
		const relayed = relayFailure(new Response('not found', { status: 404 }));

		assert.equal(relayed.status, 404);
	});

	it('400을 그대로 넘긴다 — 질문 길이 초과', () => {
		assert.equal(relayFailure(new Response('bad', { status: 400 })).status, 400);
	});

	it('401을 그대로 넘긴다 — 만료된 토큰. 이것을 봐야 재로그인을 유도한다', () => {
		assert.equal(relayFailure(new Response('unauthorized', { status: 401 })).status, 401);
	});

	it('500도 그대로 넘긴다', () => {
		assert.equal(relayFailure(new Response('boom', { status: 500 })).status, 500);
	});

	it('상태는 성공인데 본문이 없으면 502다 — 이것이 진짜 게이트웨이 오류다', () => {
		const relayed = relayFailure(new Response(null, { status: 200 }));

		assert.equal(relayed.status, 502);
	});
});
