import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { sessionTitle, toUiMessages, type CoreMessage } from './history.ts';

describe('이력 → useChat 메시지 변환', () => {
	const 이력: CoreMessage[] = [
		{ role: 'USER', content: '연차휴가는?' },
		{ role: 'ASSISTANT', content: '15일입니다.' },
		{ role: 'USER', content: '이월되나요?' },
	];

	it('역할을 소문자 useChat 규약으로 옮긴다', () => {
		assert.deepEqual(
			toUiMessages(이력).map((m) => m.role),
			['user', 'assistant', 'user'],
		);
	});

	it('순서를 보존한다', () => {
		assert.deepEqual(
			toUiMessages(이력).map((m) => m.parts[0].text),
			['연차휴가는?', '15일입니다.', '이월되나요?'],
		);
	});

	it('각 메시지에 고유한 id를 준다', () => {
		const ids = toUiMessages(이력).map((m) => m.id);
		assert.equal(new Set(ids).size, ids.length);
	});

	it('본문은 text 파트 하나로 담긴다', () => {
		assert.deepEqual(toUiMessages([{ role: 'USER', content: '질문' }])[0].parts, [
			{ type: 'text', text: '질문' },
		]);
	});

	it('빈 본문은 버린다', () => {
		assert.deepEqual(toUiMessages([{ role: 'ASSISTANT', content: '' }]), []);
	});

	it('빈 이력은 빈 배열이다', () => {
		assert.deepEqual(toUiMessages([]), []);
	});
});

describe('세션 제목', () => {
	const 기본 = { id: 'x', createdAt: '', updatedAt: '' };

	it('코어가 준 제목을 쓴다', () => {
		assert.equal(sessionTitle({ ...기본, title: '연차 문의' }), '연차 문의');
	});

	it('공백뿐인 제목은 대체 문구를 쓴다', () => {
		assert.equal(sessionTitle({ ...기본, title: '   ' }), '제목 없는 대화');
	});
});
