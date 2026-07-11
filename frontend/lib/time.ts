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
