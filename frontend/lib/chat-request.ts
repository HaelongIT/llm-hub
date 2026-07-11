/**
 * 채팅 요청 본문에서 검색 쿼리를 뽑는다. 검색 쿼리는 마지막 사용자 질문 그대로다 (S2, E3).
 *
 * 입력은 신뢰할 수 없다. messages가 누락되거나 배열이 아니면 스프레드에서 예외가 나 500이 되었다 (리뷰 F7).
 * 여기서 방어해 잘못된 입력은 빈 문자열로 떨어뜨린다 — 호출자가 그 전에 형식을 검증해 400으로 거부한다.
 */
type UiMessage = { role?: string; parts?: Array<{ type?: string; text?: string }> };

export function lastUserText(messages: unknown): string {
	if (!Array.isArray(messages)) return '';
	const lastUser = [...(messages as UiMessage[])].reverse().find((m) => m?.role === 'user');
	return (
		lastUser?.parts
			?.filter((p) => p?.type === 'text')
			.map((p) => p.text ?? '')
			.join('') ?? ''
	);
}
