package com.llmhub.chat.config;

import com.llmhub.audit.AuditScope;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param contextTurns LLM 컨텍스트에 담을 최근 턴 수 (S2, PERF-5).
 * @param maxContextTokens 이력이 차지할 수 있는 토큰 상한. 턴 수만으로는 길이가 잡히지 않는다 (PERF-5).
 * @param maxQuestionChars 질문 길이 상한. 잘라낼 수 없으므로 거부한다 (PERF-5, SEC-4).
 * @param auditScope 감사 기록 범위. 스키마 변경 없이 조절된다 (E5).
 */
@ConfigurationProperties(prefix = "llmhub.chat")
public record ChatProperties(
		int contextTurns, int maxContextTokens, int maxQuestionChars, AuditScope auditScope) {}
