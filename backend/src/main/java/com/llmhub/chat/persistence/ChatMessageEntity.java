package com.llmhub.chat.persistence;

import com.llmhub.chat.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_message")
class ChatMessageEntity {

	@Id private UUID id;

	@Column(name = "session_id", nullable = false)
	private UUID sessionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private String content;

	/** 근거 스냅샷 (jsonb). assistant 메시지에만 있다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "sources_json")
	private String sourcesJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChatMessageEntity() {}

	ChatMessageEntity(UUID id, UUID sessionId, Role role, String content, String sourcesJson, Instant createdAt) {
		this.id = id;
		this.sessionId = sessionId;
		this.role = role;
		this.content = content;
		this.sourcesJson = sourcesJson;
		this.createdAt = createdAt;
	}

	Role getRole() {
		return role;
	}

	String getContent() {
		return content;
	}
}
