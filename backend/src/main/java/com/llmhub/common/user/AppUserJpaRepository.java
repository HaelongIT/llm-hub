package com.llmhub.common.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {
	Optional<AppUserEntity> findByKeycloakSubject(String keycloakSubject);
}
