package com.llmhub.chat.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChatSessionJpaRepository extends JpaRepository<ChatSessionEntity, UUID> {

	List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

	Optional<ChatSessionEntity> findByIdAndUserId(UUID id, UUID userId);
}

interface ChatMessageJpaRepository extends JpaRepository<ChatMessageEntity, UUID> {
	List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
