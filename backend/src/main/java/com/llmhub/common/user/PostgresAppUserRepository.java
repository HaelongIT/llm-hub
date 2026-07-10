package com.llmhub.common.user;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 블로킹 JPA. 호출자는 격리 스케줄러에서 부른다 (S13, E12). */
@Repository
public class PostgresAppUserRepository implements AppUserRepository {

	private final AppUserJpaRepository jpaRepository;
	private final Clock clock;

	PostgresAppUserRepository(AppUserJpaRepository jpaRepository, Clock clock) {
		this.jpaRepository = jpaRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public UUID ensureExists(String keycloakSubject) {
		return jpaRepository
				.findByKeycloakSubject(keycloakSubject)
				.map(AppUserEntity::getId)
				.orElseGet(
						() ->
								jpaRepository
										.save(new AppUserEntity(UUID.randomUUID(), keycloakSubject, Instant.now(clock)))
										.getId());
	}
}
