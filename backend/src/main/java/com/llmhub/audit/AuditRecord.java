package com.llmhub.audit;

/**
 * 감사 레코드 (S5).
 *
 * @param requesterId 사용자 식별자의 <b>값 복사</b>다. FK가 아니다 — 사용자 삭제와 무관하게 유지되어야 한다.
 * @param sourcesJson 근거 스냅샷.
 * @param outcome 대화가 완료·취소·오류 중 무엇으로 끝났는지 (R-5).
 */
public record AuditRecord(
		String traceId,
		String requesterId,
		String question,
		String answer,
		String sourcesJson,
		AuditOutcome outcome) {}
