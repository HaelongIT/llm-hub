package com.llmhub.audit;

/**
 * 감사 기록 범위 (E5). 범위 변경이 스키마 변경을 요구하지 않는다.
 *
 * <p>v0 기본은 {@link #FULL}이다.
 */
public enum AuditScope {
	/** 질문·응답 전문을 기록한다. */
	FULL,

	/** 전문을 남기지 않는다. 추적 ID·요청자·근거는 유지되어 추적과 감사가 가능하다. */
	MINIMAL
}
