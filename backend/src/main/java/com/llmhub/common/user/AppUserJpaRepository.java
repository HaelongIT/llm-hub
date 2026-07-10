package com.llmhub.common.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {

	Optional<AppUserEntity> findByKeycloakSubject(String keycloakSubject);

	/**
	 * 삽입을 원자적으로 만든다. 같은 사용자의 첫 요청이 동시에 여러 개 들어와도 하나만 들어간다.
	 *
	 * <p>애플리케이션에서 락을 잡는 대신 DB의 unique 제약을 그대로 쓴다. 경합에서 진 트랜잭션은 아무것도 하지 않고, 곧이어
	 * 이긴 쪽의 행을 읽는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
			value =
					"""
					insert into app_user (id, keycloak_subject, created_at)
					values (:id, :subject, :createdAt)
					on conflict (keycloak_subject) do nothing
					""",
			nativeQuery = true)
	void insertIfAbsent(
			@Param("id") UUID id, @Param("subject") String subject, @Param("createdAt") Instant createdAt);
}
