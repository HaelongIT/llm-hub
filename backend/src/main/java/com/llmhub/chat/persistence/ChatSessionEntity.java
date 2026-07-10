package com.llmhub.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_session")
class ChatSessionEntity {

	@Id private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private String title;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ChatSessionEntity() {}

	ChatSessionEntity(UUID id, UUID userId, String title, Instant now) {
		this.id = id;
		this.userId = userId;
		this.title = title;
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID getId() {
		return id;
	}

	UUID getUserId() {
		return userId;
	}

	String getTitle() {
		return title;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}
}
