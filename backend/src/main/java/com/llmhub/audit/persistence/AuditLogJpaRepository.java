package com.llmhub.audit.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {}
