package com.llmhub.chat.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.Message;
import com.llmhub.chat.Role;
import com.llmhub.common.user.AppUserRepository;
import com.llmhub.support.PostgresInitializer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

/**
 * 한 턴 저장의 <b>원자성</b>(R-12)과 세션 <b>활동 시각 갱신</b>(R-10)을 실제 PostgreSQL로 확인한다.
 *
 * <p>원자성은 {@code @Transactional} 프록시가 있어야 성립하므로 대역이 아니라 오토와이어된 빈을 쓴다. 시각 갱신은
 * 결정적으로 보려고 시계를 {@link TestConfiguration}에서 가변 빈으로 주입한다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PostgresInitializer.class)
class PostgresChatHistoryRepositoryTest {

	@Autowired private ChatHistoryRepository repository;
	@Autowired private AppUserRepository users;
	@Autowired private 가변시계 clock;

	@Test
	@DisplayName("한 턴을 저장하면 세션 활동 시각이 갱신된다 (R-10)")
	void 턴_저장이_활동시각을_갱신한다() {
		UUID sessionId = 새_세션();
		Instant 생성시각 = 세션_갱신시각(sessionId);

		clock.지난다(Duration.ofMinutes(3));
		repository.appendTurn(sessionId, Message.user("연차휴가는?"), Message.assistant("15일입니다."), "[]");

		assertThat(세션_갱신시각(sessionId))
				.as("메시지가 붙으면 활동 시각이 갱신된다 — 안 하면 '최근 활동순'이 '생성순'이 된다 (R-10)")
				.isAfter(생성시각);
	}

	@Test
	@DisplayName("답변 저장이 실패하면 질문도 남지 않는다 — 한 트랜잭션이다 (R-12)")
	void 질문과_답변은_한_트랜잭션이다() {
		UUID sessionId = 새_세션();

		// 답변 content를 null로 만들어 두 번째 insert가 not-null 제약을 위반하게 한다. 별도 트랜잭션이었다면
		// 질문은 이미 커밋됐을 것이다.
		assertThatThrownBy(
						() ->
								repository.appendTurn(
										sessionId, Message.user("답변 없는 질문"), new Message(Role.ASSISTANT, null), "[]"))
				.as("답변 저장이 실패해야 한다");

		assertThat(repository.history(sessionId))
				.as("답변 저장이 실패하면 질문도 롤백된다 (R-12)")
				.isEmpty();
	}

	private UUID 마지막_사용자;

	private UUID 새_세션() {
		마지막_사용자 = users.ensureExists("subj-" + UUID.randomUUID());
		return repository.createSession(마지막_사용자, "제목");
	}

	private Instant 세션_갱신시각(UUID sessionId) {
		return repository.sessionsOf(마지막_사용자).stream()
				.filter(s -> s.id().equals(sessionId))
				.findFirst()
				.orElseThrow()
				.updatedAt();
	}

	@TestConfiguration
	static class 설정 {
		@Bean
		@Primary
		가변시계 가변시계() {
			return new 가변시계(Instant.parse("2026-07-11T00:00:00Z"));
		}
	}

	/** 테스트가 시간을 앞으로 감을 수 있는 시계. */
	static final class 가변시계 extends Clock {
		private volatile Instant now;

		가변시계(Instant start) {
			this.now = start;
		}

		void 지난다(Duration d) {
			this.now = this.now.plus(d);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
