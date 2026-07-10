package com.llmhub.common.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUserEntity {

	@Id private UUID id;

	@Column(name = "keycloak_subject", nullable = false, unique = true)
	private String keycloakSubject;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AppUserEntity() {}

	AppUserEntity(UUID id, String keycloakSubject, Instant createdAt) {
		this.id = id;
		this.keycloakSubject = keycloakSubject;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}
}
