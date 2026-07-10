/**
 * 코어의 대화 이력을 AI SDK가 그리는 메시지 모양으로 옮긴다.
 *
 * 코어는 `{ role: "USER" | "ASSISTANT", content: string }`을 준다(docs/03의 chat_message).
 * `useChat`은 `parts` 배열을 가진 메시지를 그린다.
 */

/** 코어 `/api/sessions/{id}/messages` 응답의 한 건. */
export type CoreMessage = {
	role: 'USER' | 'ASSISTANT';
	content: string;
};

/** `useChat`의 `setMessages`에 넣을 모양. */
export type UiMessage = {
	id: string;
	role: 'user' | 'assistant';
	parts: Array<{ type: 'text'; text: string }>;
};

/** 사이드바에 그릴 세션 한 줄. 코어의 SessionSummary와 같은 모양이다. */
export type SessionSummary = {
	id: string;
	title: string;
	createdAt: string;
	updatedAt: string;
};

/**
 * 순서를 보존한다. 코어가 오래된 것부터 정렬해 주므로 그대로 둔다.
 *
 * 근거(sources)는 다시 그리지 않는다. 저장된 스냅샷은 감사와 이력 조회용이고, 화면의 근거는
 * 그 응답이 스트리밍될 때 받은 `data-sources` 파트에서 온다.
 */
export function toUiMessages(history: CoreMessage[]): UiMessage[] {
	return history
		.filter((message) => message.content.length > 0)
		.map((message, index) => ({
			id: `history-${index}`,
			role: message.role === 'USER' ? 'user' : 'assistant',
			parts: [{ type: 'text', text: message.content }],
		}));
}

/** 세션 제목. 코어가 질문 앞부분을 잘라 넣지만, 빈 제목도 방어한다. */
export function sessionTitle(session: SessionSummary): string {
	const title = session.title.trim();
	return title.length > 0 ? title : '제목 없는 대화';
}
