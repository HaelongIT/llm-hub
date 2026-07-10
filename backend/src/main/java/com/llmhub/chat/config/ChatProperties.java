package com.llmhub.chat.config;

import com.llmhub.audit.AuditScope;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param contextTurns LLM 컨텍스트에 담을 최근 턴 수 (S2, PERF-5).
 * @param auditScope 감사 기록 범위. 스키마 변경 없이 조절된다 (E5).
 */
@ConfigurationProperties(prefix = "llmhub.chat")
public record ChatProperties(int contextTurns, AuditScope auditScope) {}
