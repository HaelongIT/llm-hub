import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { nextTheme, THEME_STORAGE_KEY } from './theme.ts';

describe('테마 토글', () => {
	it('라이트에서 다크로', () => {
		assert.equal(nextTheme('light'), 'dark');
	});
	it('다크에서 라이트로', () => {
		assert.equal(nextTheme('dark'), 'light');
	});
	it('두 번 뒤집으면 제자리', () => {
		assert.equal(nextTheme(nextTheme('light')), 'light');
	});
	it('저장 키는 FOUC 가드 스크립트와 같은 값으로 고정', () => {
		assert.equal(THEME_STORAGE_KEY, 'llmhub-theme');
	});
});
