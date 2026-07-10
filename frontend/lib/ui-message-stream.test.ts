import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	DONE_LINE,
	parseSseFrame,
	toSseLine,
	UiMessageStreamTranslator,
	UI_MESSAGE_STREAM_HEADERS,
} from './ui-message-stream.ts';

describe('코어 SSE 프레임 파싱', () => {
	it('event와 data를 분리한다', () => {
		assert.deepEqual(parseSseFrame('event:text\ndata:연차휴가는'), {
			event: 'text',
			data: '연차휴가는',
		});
	});

	it('여러 줄 data를 이어 붙인다', () => {
		assert.deepEqual(parseSseFrame('event:error\ndata:첫 줄\ndata:둘째 줄'), {
			event: 'error',
			data: '첫 줄\n둘째 줄',
		});
	});

	it('event가 없는 프레임은 무시한다', () => {
		assert.equal(parseSseFrame('data:하트비트'), null);
	});
});

describe('AI SDK 파트 번역', () => {
	const 번역 = () => new UiMessageStreamTranslator('msg-1');

	it('첫 이벤트 앞에 start 파트가 한 번 붙는다', () => {
		const t = 번역();
		const 첫번째 = t.translate({ event: 'sources', data: '[]' });
		const 두번째 = t.translate({ event: 'text', data: '가' });

		assert.equal(첫번째[0].type, 'start');
		assert.ok(!두번째.some((p) => p.type === 'start'), 'start는 한 번만');
	});

	it('sources가 첫 text보다 먼저 나온다 (S6)', () => {
		const t = 번역();
		const parts = [
			...t.translate({ event: 'sources', data: '[{"documentId":"doc-1"}]' }),
			...t.translate({ event: 'text', data: '연차' }),
		];
		const types = parts.map((p) => p.type);

		assert.ok(types.indexOf('data-sources') < types.indexOf('text-delta'));
	});

	it('sources 파트가 근거 JSON을 그대로 싣는다', () => {
		const t = 번역();
		const parts = t.translate({
			event: 'sources',
			data: '[{"documentId":"doc-1","documentName":"규정.txt","location":"0","text":"연차휴가는 15일","score":1.5}]',
		});
		const sources = parts.find((p) => p.type === 'data-sources');

		assert.deepEqual(sources?.data, [
			{ documentId: 'doc-1', documentName: '규정.txt', location: '0', text: '연차휴가는 15일', score: 1.5 },
		]);
	});

	it('text 블록은 한 번만 열린다', () => {
		const t = 번역();
		const parts = [
			...t.translate({ event: 'text', data: '연차' }),
			...t.translate({ event: 'text', data: '휴가' }),
		];

		assert.equal(parts.filter((p) => p.type === 'text-start').length, 1);
		assert.deepEqual(
			parts.filter((p) => p.type === 'text-delta').map((p) => p.delta),
			['연차', '휴가'],
		);
	});

	it('done이면 text-end와 finish로 닫는다', () => {
		const t = 번역();
		t.translate({ event: 'text', data: '연차' });
		const parts = t.translate({ event: 'done', data: 'trace-1' });

		assert.deepEqual(
			parts.map((p) => p.type),
			['text-end', 'finish'],
		);
	});

	it('error면 열린 텍스트를 닫고 error 파트를 낸다', () => {
		const t = 번역();
		t.translate({ event: 'text', data: '연차' });
		const parts = t.translate({ event: 'error', data: '게이트웨이 다운' });

		assert.deepEqual(
			parts.map((p) => p.type),
			['text-end', 'error'],
		);
		assert.equal(parts[1].errorText, '게이트웨이 다운');
	});

	it('텍스트가 없는 상태의 error는 text-end를 만들지 않는다', () => {
		const t = 번역();
		const parts = t.translate({ event: 'error', data: 'ES 다운' });

		assert.deepEqual(
			parts.map((p) => p.type),
			['start', 'error'],
		);
	});

	it('종료 후의 이벤트는 무시한다', () => {
		const t = 번역();
		t.translate({ event: 'done', data: 'trace-1' });

		assert.deepEqual(t.translate({ event: 'text', data: '늦은 조각' }), []);
	});

	it('done도 error도 없이 끊기면 error로 닫는다', () => {
		const t = 번역();
		t.translate({ event: 'text', data: '연차' });
		const parts = t.finish();

		assert.deepEqual(
			parts.map((p) => p.type),
			['text-end', 'error'],
		);
	});

	it('정상 종료 후 finish()는 아무것도 만들지 않는다', () => {
		const t = 번역();
		t.translate({ event: 'done', data: 'trace-1' });

		assert.deepEqual(t.finish(), []);
	});
});

describe('와이어 포맷', () => {
	it('파트를 data: 줄로 감싼다', () => {
		assert.equal(toSseLine({ type: 'finish' }), 'data: {"type":"finish"}\n\n');
	});

	it('스트림 끝은 [DONE]이다', () => {
		assert.equal(DONE_LINE, 'data: [DONE]\n\n');
	});

	it('useChat이 요구하는 헤더가 있다', () => {
		assert.equal(UI_MESSAGE_STREAM_HEADERS['x-vercel-ai-ui-message-stream'], 'v1');
		assert.equal(UI_MESSAGE_STREAM_HEADERS['Content-Type'], 'text/event-stream');
	});
});
