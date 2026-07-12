'use client';

import { useEffect, useState } from 'react';

import { nextTheme, THEME_STORAGE_KEY, type Theme } from '@/lib/theme';

/**
 * 사이드바 테마 토글. 시그니처가 아니므로 조용한 모노 글리프 하나.
 *
 * 현재 테마는 <html data-theme>에서 읽는다 (FOUC 가드가 페인트 전에 확정해 둔 값).
 * 하이드레이션 불일치를 피하려 글리프는 테마와 무관하게 고정(◐)하고,
 * 마운트 후에만 방향(라벨)을 확정한다.
 */
export function ThemeToggle() {
	const [theme, setTheme] = useState<Theme | null>(null);

	useEffect(() => {
		setTheme(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
	}, []);

	function toggle() {
		const current: Theme =
			document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
		const next = nextTheme(current);
		document.documentElement.dataset.theme = next;
		try {
			localStorage.setItem(THEME_STORAGE_KEY, next);
		} catch {
			// 저장이 막히면(프라이빗 모드 등) 이번 세션에만 적용한다.
		}
		setTheme(next);
	}

	const label =
		theme === null ? '테마 전환' : theme === 'dark' ? '밝은 테마로' : '어두운 테마로';

	return (
		<button type="button" className="themetoggle" onClick={toggle} aria-label={label} title={label}>
			<span aria-hidden="true">◐</span>
		</button>
	);
}
