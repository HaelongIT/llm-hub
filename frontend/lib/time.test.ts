import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { dateGroup, relativeTime } from './time.ts';

const NOW = Date.parse('2026-07-11T12:00:00Z');

describe('상대 시각', () => {
	it('1분 안쪽이면 방금', () => {
		assert.equal(relativeTime('2026-07-11T11:59:30Z', NOW), '방금');
	});

	it('분 단위', () => {
		assert.equal(relativeTime('2026-07-11T11:57:00Z', NOW), '3분 전');
	});

	it('시간 단위', () => {
		assert.equal(relativeTime('2026-07-11T09:00:00Z', NOW), '3시간 전');
	});

	it('일 단위', () => {
		assert.equal(relativeTime('2026-07-09T12:00:00Z', NOW), '2일 전');
	});

	it('미래 시각은 방금으로 막는다(음수 방지)', () => {
		assert.equal(relativeTime('2026-07-11T12:05:00Z', NOW), '방금');
	});

	it('잘못된 값은 빈 문자열', () => {
		assert.equal(relativeTime('not-a-date', NOW), '');
	});
});

describe('날짜 그룹', () => {
	// 로컬 생성자로 만들어 실행 TZ와 무관하게 달력일 차이를 고정한다.
	const now = new Date(2026, 6, 11, 14, 0).getTime();
	const at = (y: number, m: number, d: number) => new Date(y, m, d, 9, 0).toISOString();

	it('같은 날은 오늘', () => {
		assert.equal(dateGroup(at(2026, 6, 11), now), '오늘');
	});
	it('하루 전은 어제', () => {
		assert.equal(dateGroup(at(2026, 6, 10), now), '어제');
	});
	it('며칠 전은 이번 주', () => {
		assert.equal(dateGroup(at(2026, 6, 8), now), '이번 주');
	});
	it('일주일 넘으면 이전', () => {
		assert.equal(dateGroup(at(2026, 6, 1), now), '이전');
	});
});
