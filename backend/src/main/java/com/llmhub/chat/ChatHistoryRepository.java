package com.llmhub.chat;

import java.util.List;
import java.util.UUID;

/**
 * 세션과 메시지 (S2). 사용자 소유이며 삭제하면 메시지도 함께 사라진다.
 *
 * <p><b>블로킹이다.</b> 스트림 완료 시점의 저장은 격리 스케줄러로 넘기고 스트림은 대기하지 않는다 (S13).
 */
public interface ChatHistoryRepository {

	UUID createSession(UUID userId, String title);

	/** 세션 삭제. 메시지는 cascade로 사라지고 감사 로그는 영향받지 않는다 (S5). */
	void deleteSession(UUID sessionId);

	/** 이 사용자가 소유한 세션. 최근 갱신 순. */
	List<SessionSummary> sessionsOf(UUID userId);

	/** 소유자가 맞는지 확인한다. 남의 세션은 존재하지 않는 것처럼 취급한다. */
	boolean isOwnedBy(UUID sessionId, UUID userId);

	/** 오래된 것부터 정렬된 이력. */
	List<Message> history(UUID sessionId);

	/**
	 * @param sourcesJson assistant 응답의 근거 스냅샷. "그 응답 시점에 무엇을 봤나"의 박제다. user 메시지면 null.
	 */
	void append(UUID sessionId, Message message, String sourcesJson);

	/**
	 * 한 턴(질문 + 답변)을 <b>한 트랜잭션으로</b> 저장하고 세션 활동 시각을 갱신한다.
	 *
	 * <p>질문과 답변을 별도 트랜잭션으로 넣으면, 답변 저장이 실패했을 때 <b>답변 없는 질문</b>만 이력에 남는다 (R-12).
	 * 하나로 묶어 함께 커밋하거나 함께 롤백한다. 세션 {@code updated_at}도 여기서 갱신해 "최근 활동순"을 유지한다
	 * (R-10).
	 *
	 * @param sourcesJson answer의 근거 스냅샷.
	 */
	void appendTurn(UUID sessionId, Message user, Message assistant, String sourcesJson);
}
