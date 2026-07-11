/**
 * 세션 활동 시각을 사람이 읽는 상대 시각으로. 사이드바에 모노로 표시한다.
 *
 * 순수 함수다. `nowMs`를 인자로 받아 테스트에서 시간을 고정한다.
 */
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

export function relativeTime(iso: string, nowMs: number = Date.now()): string {
	const then = new Date(iso).getTime();
	if (Number.isNaN(then)) return '';

	const diff = Math.max(0, nowMs - then);
	if (diff < MIN) return '방금';
	if (diff < HOUR) return `${Math.floor(diff / MIN)}분 전`;
	if (diff < DAY) return `${Math.floor(diff / HOUR)}시간 전`;
	if (diff < 7 * DAY) return `${Math.floor(diff / DAY)}일 전`;

	const d = new Date(then);
	return `${d.getMonth() + 1}월 ${d.getDate()}일`;
}

/** 세션 목록을 묶는 날짜 그룹. 로컬 달력일 기준. */
export function dateGroup(iso: string, nowMs: number = Date.now()): string {
	const then = new Date(iso).getTime();
	if (Number.isNaN(then)) return '이전';

	const startOfDay = (ms: number) => {
		const d = new Date(ms);
		d.setHours(0, 0, 0, 0);
		return d.getTime();
	};
	const days = Math.floor((startOfDay(nowMs) - startOfDay(then)) / DAY);
	if (days <= 0) return '오늘';
	if (days === 1) return '어제';
	if (days <= 6) return '이번 주';
	return '이전';
}

/** 그룹 표시 순서. 목록 렌더에 쓴다. */
export const DATE_GROUP_ORDER = ['오늘', '어제', '이번 주', '이전'] as const;
