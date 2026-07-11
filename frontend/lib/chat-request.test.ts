import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { lastUserText } from './chat-request.ts';

describe('lastUserText', () => {
	it('messages가 undefined면 빈 문자열 — 스프레드 예외가 없다 (F7)', () => {
		assert.equal(lastUserText(undefined), '');
	});

	it('messages가 배열이 아니면 빈 문자열', () => {
		assert.equal(lastUserText({ foo: 'bar' }), '');
	});

	it('빈 배열은 빈 문자열', () => {
		assert.equal(lastUserText([]), '');
	});

	it('마지막 사용자 메시지의 text를 뽑는다', () => {
		const messages = [
			{ role: 'user', parts: [{ type: 'text', text: '첫 질문' }] },
			{ role: 'assistant', parts: [{ type: 'text', text: '답변' }] },
			{ role: 'user', parts: [{ type: 'text', text: '둘째 질문' }] },
		];
		assert.equal(lastUserText(messages), '둘째 질문');
	});

	it('사용자 메시지가 없으면 빈 문자열', () => {
		const messages = [{ role: 'assistant', parts: [{ type: 'text', text: '답변' }] }];
		assert.equal(lastUserText(messages), '');
	});
});
