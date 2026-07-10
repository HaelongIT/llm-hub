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

	/**
	 * "찾고 없으면 삽입"은 경합한다. 같은 사용자의 첫 요청이 동시에 여러 개 들어오면 전부 "없다"고 판단하고 전부 삽입해
	 * unique 제약을 위반한다. 부하 테스트가 이 버그를 잡았다.
	 *
	 * <p>삽입을 {@code on conflict do nothing}으로 원자화하고, 그 뒤에 읽는다. 경합에서 진 쪽도 이긴 쪽의 행을
	 * 그대로 본다.
	 */
	@Override
	@Transactional
	public UUID ensureExists(String keycloakSubject) {
		return jpaRepository
				.findByKeycloakSubject(keycloakSubject)
				.map(AppUserEntity::getId)
				.orElseGet(() -> insertThenRead(keycloakSubject));
	}

	private UUID insertThenRead(String keycloakSubject) {
		jpaRepository.insertIfAbsent(UUID.randomUUID(), keycloakSubject, Instant.now(clock));
		return jpaRepository
				.findByKeycloakSubject(keycloakSubject)
				.map(AppUserEntity::getId)
				.orElseThrow(() -> new IllegalStateException("사용자 삽입 후 조회 실패: " + keycloakSubject));
	}
}
