package com.llmhub.audit;

/**
 * 감사 로그 저장 (S5).
 *
 * <p>대화 이력과 <b>독립된 수명주기</b>를 갖는다. 기록은 CHAT 응답 완료 시점에 비동기로 트리거되며, 실패해도 사용자 응답을
 * 되돌리지 않는다 (REL-2).
 *
 * <p><b>블로킹이다.</b> 격리 스케줄러로 넘겨 부른다 (S13).
 */
public interface AuditLogRepository {

	void record(AuditRecord record);
}
