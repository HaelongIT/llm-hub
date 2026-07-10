package com.llmhub.audit.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code audit_log} 테이블. <b>어떤 FK도 없다</b> (S5).
 *
 * <p>{@code requesterId}가 문자열인 것은 실수가 아니다. 값 복사여야 사용자·세션 삭제와 무관하게 남는다.
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntity {

	@Id private UUID id;

	@Column(name = "trace_id", nullable = false)
	private String traceId;

	@Column(name = "requester_id", nullable = false)
	private String requesterId;

	@Column private String question;

	@Column private String answer;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "sources_json")
	private String sourcesJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AuditLogEntity() {}

	AuditLogEntity(
			UUID id,
			String traceId,
			String requesterId,
			String question,
			String answer,
			String sourcesJson,
			Instant createdAt) {
		this.id = id;
		this.traceId = traceId;
		this.requesterId = requesterId;
		this.question = question;
		this.answer = answer;
		this.sourcesJson = sourcesJson;
		this.createdAt = createdAt;
	}
}
