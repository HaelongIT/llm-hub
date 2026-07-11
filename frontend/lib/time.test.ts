import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { relativeTime } from './time.ts';

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
