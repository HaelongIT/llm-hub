package com.llmhub.chat.persistence;

import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.Message;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로킹 JPA 접근을 이 계층에 가둔다 (S13, E12).
 *
 * <p>세션 삭제 시 메시지는 DB의 {@code on delete cascade}로 사라진다. 감사 로그는 FK가 없으므로 영향받지 않는다
 * (S5).
 */
@Repository
public class PostgresChatHistoryRepository implements ChatHistoryRepository {

	private final ChatSessionJpaRepository sessions;
	private final ChatMessageJpaRepository messages;
	private final Clock clock;

	PostgresChatHistoryRepository(
			ChatSessionJpaRepository sessions, ChatMessageJpaRepository messages, Clock clock) {
		this.sessions = sessions;
		this.messages = messages;
		this.clock = clock;
	}

	@Override
	@Transactional
	public UUID createSession(UUID userId, String title) {
		return sessions.save(new ChatSessionEntity(UUID.randomUUID(), userId, title, Instant.now(clock))).getId();
	}

	@Override
	@Transactional
	public void deleteSession(UUID sessionId) {
		sessions.deleteById(sessionId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Message> history(UUID sessionId) {
		return messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
				.map(e -> new Message(e.getRole(), e.getContent()))
				.toList();
	}

	@Override
	@Transactional
	public void append(UUID sessionId, Message message, String sourcesJson) {
		messages.save(
				new ChatMessageEntity(
						UUID.randomUUID(), sessionId, message.role(), message.content(), sourcesJson, Instant.now(clock)));
	}
}
