/**
 * 열람실 테마. 라이트(주간) / 다크(밤 열람실) 두 가지.
 *
 * 정체성은 하나고, 색 토큰만 리매핑한다(globals.css의 :root[data-theme]).
 * 순수 함수라 테스트에서 시간·DOM 없이 검증한다.
 */
export type Theme = 'light' | 'dark';

/** 사용자 테마 선택을 저장하는 localStorage 키. FOUC 가드 스크립트(layout.tsx)도 같은 키를 읽는다. */
export const THEME_STORAGE_KEY = 'llmhub-theme';

/** 현재 테마의 반대. 사이드바 토글이 쓴다. */
export function nextTheme(current: Theme): Theme {
	return current === 'dark' ? 'light' : 'dark';
}
