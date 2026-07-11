package com.llmhub.audit.persistence;

import com.llmhub.audit.AuditLogRepository;
import com.llmhub.audit.AuditRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 블로킹 JPA 접근을 이 계층에 가둔다 (S13, E12). */
@Repository
public class PostgresAuditLogRepository implements AuditLogRepository {

	private final AuditLogJpaRepository jpaRepository;
	private final Clock clock;

	PostgresAuditLogRepository(AuditLogJpaRepository jpaRepository, Clock clock) {
		this.jpaRepository = jpaRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void record(AuditRecord record) {
		jpaRepository.save(
				new AuditLogEntity(
						UUID.randomUUID(),
						record.traceId(),
						record.requesterId(),
						record.question(),
						record.answer(),
						record.sourcesJson(),
						record.outcome().name(),
						Instant.now(clock)));
	}
}
