package com.llmhub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.audit.AuditLogRepository;
import com.llmhub.audit.AuditRecord;
import com.llmhub.audit.AuditScope;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.search.SearchService;
import com.llmhub.search.Source;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * REQ-CHAT: 스트림 완료 후 메시지 저장(sources 스냅샷 포함). 저장을 강제 실패시켜도 사용자는 정상 응답. 블로킹 저장이
 * 논블로킹 스레드를 점유하지 않음.
 *
 * <p>REQ-AUDIT: 채팅 1회 → 감사 레코드 1건. 감사 저장 실패 → 사용자 응답 정상 + 경고 로그. 기록 범위 '최소'.
 *
 * <p>S13: 스트림은 저장을 <b>기다리지 않는다</b>. REL-2: 저장 실패가 사용자 응답을 되돌리지 않는다.
 */
class ChatPersistenceTest {

	private static final UUID 세션ID = UUID.randomUUID();
	private static final String 요청자 = "user-42";
	private static final List<Source> 검색결과 =
			List.of(new Source("doc-1", "규정.txt", "0", "연차휴가는 15일이다.", 1.5));

	private final 이력_대역 이력 = new 이력_대역();
	private final 감사_대역 감사 = new 감사_대역();

	private ChatService 서비스(AuditScope scope) {
		return new ChatService(
				검색_대역(),
				ChatClient.builder(모델("연차", "휴가는 15일입니다.")).build(),
				new RecentTurnsContextAssembler(2, 100_000),
				이력,
				감사,
				scope,
				java.time.Duration.ofSeconds(60));
	}

	private List<ChatEvent> 스트리밍한다(ChatService service) {
		return service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();
	}

	@Test
	@DisplayName("스트림 완료 후 user/assistant 메시지가 저장된다. assistant에는 sources 스냅샷이 붙는다")
	void 이력이_저장된다() throws InterruptedException {
		스트리밍한다(서비스(AuditScope.FULL));
		이력.저장_완료.await(5, TimeUnit.SECONDS);

		assertThat(이력.저장된).hasSize(2);
		assertThat(이력.저장된.get(0).message().role()).isEqualTo(Role.USER);
		assertThat(이력.저장된.get(0).sourcesJson()).as("질문에는 근거가 없다").isNull();
		assertThat(이력.저장된.get(1).message().content()).isEqualTo("연차휴가는 15일입니다.");
		assertThat(이력.저장된.get(1).sourcesJson())
				.as("그 응답 시점에 무엇을 봤나의 박제다")
				.contains("doc-1")
				.contains("연차휴가는 15일이다.");
	}

	@Test
	@DisplayName("채팅 1회에 감사 레코드가 1건 생성된다")
	void 감사_레코드가_한_건_생긴다() throws InterruptedException {
		스트리밍한다(서비스(AuditScope.FULL));
		감사.기록_완료.await(5, TimeUnit.SECONDS);

		assertThat(감사.기록된).singleElement().satisfies(r -> {
			assertThat(r.traceId()).isEqualTo("trace-1");
			assertThat(r.requesterId()).isEqualTo(요청자);
			assertThat(r.question()).isEqualTo("연차휴가는?");
			assertThat(r.answer()).isEqualTo("연차휴가는 15일입니다.");
			assertThat(r.sourcesJson()).contains("doc-1");
		});
	}

	@Test
	@DisplayName("기록 범위가 '최소'면 질문·응답 전문을 남기지 않는다 (스키마 변경 없이)")
	void 최소_범위는_전문을_남기지_않는다() throws InterruptedException {
		스트리밍한다(서비스(AuditScope.MINIMAL));
		감사.기록_완료.await(5, TimeUnit.SECONDS);

		assertThat(감사.기록된).singleElement().satisfies(r -> {
			assertThat(r.question()).isNull();
			assertThat(r.answer()).isNull();
			assertThat(r.traceId()).as("추적은 여전히 가능해야 한다 (REL-3)").isEqualTo("trace-1");
			assertThat(r.requesterId()).isEqualTo(요청자);
			assertThat(r.sourcesJson()).as("어떤 근거를 봤는지는 감사의 핵심이다").contains("doc-1");
		});
	}

	@Test
	@DisplayName("저장은 격리 스케줄러에서 실행된다")
	void 저장은_격리_스케줄러에서_돈다() throws InterruptedException {
		스트리밍한다(서비스(AuditScope.FULL));
		이력.저장_완료.await(5, TimeUnit.SECONDS);
		감사.기록_완료.await(5, TimeUnit.SECONDS);

		assertThat(이력.실행_스레드).allSatisfy(t -> assertThat(t).startsWith("boundedElastic-"));
		assertThat(감사.실행_스레드).allSatisfy(t -> assertThat(t).startsWith("boundedElastic-"));
	}

	@Test
	@DisplayName("스트림은 저장이 끝나기를 기다리지 않는다")
	void 스트림은_저장을_기다리지_않는다() throws InterruptedException {
		이력.멈춤 = new CountDownLatch(1);

		List<ChatEvent> events = 스트리밍한다(서비스(AuditScope.FULL));

		assertThat(events)
				.as("저장이 아직 진행 중인데도 스트림은 이미 done으로 끝났다 (S13)")
				.last()
				.isEqualTo(new ChatEvent.Done("trace-1"));
		assertThat(이력.저장된).isEmpty();

		이력.멈춤.countDown();
		이력.저장_완료.await(5, TimeUnit.SECONDS);
		assertThat(이력.저장된).hasSize(2);
	}

	@Test
	@DisplayName("이력 저장이 실패해도 사용자는 정상 응답을 받는다")
	void 이력_저장_실패가_응답을_되돌리지_않는다() {
		이력.실패한다 = true;

		List<ChatEvent> events = 스트리밍한다(서비스(AuditScope.FULL));

		assertThat(events).last().isEqualTo(new ChatEvent.Done("trace-1"));
		assertThat(events).noneMatch(ChatEvent.Error.class::isInstance);
	}

	@Test
	@DisplayName("감사 저장이 실패해도 사용자는 정상 응답을 받는다")
	void 감사_저장_실패가_응답을_되돌리지_않는다() {
		감사.실패한다 = true;

		List<ChatEvent> events = 스트리밍한다(서비스(AuditScope.FULL));

		assertThat(events).last().isEqualTo(new ChatEvent.Done("trace-1"));
	}

	@Test
	@DisplayName("LLM 장애로 스트림이 error로 끝나면 아무것도 저장하지 않는다")
	void 장애시에는_저장하지_않는다() throws InterruptedException {
		ChatService service =
				new ChatService(
						검색_대역(),
						ChatClient.builder(고장난_모델()).build(),
						new RecentTurnsContextAssembler(2, 100_000),
						이력,
						감사,
						AuditScope.FULL,
						java.time.Duration.ofSeconds(60));

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(events).last().isInstanceOf(ChatEvent.Error.class);
		Thread.sleep(200);
		assertThat(이력.저장된).as("실패한 대화를 이력으로 남기지 않는다").isEmpty();
		assertThat(감사.기록된).isEmpty();
	}

	// ─── 대역 ───

	private SearchService 검색_대역() {
		return new SearchService(q -> q, new 대역_임베딩(), (t, v, tags, k) -> 검색결과, 5);
	}

	private static ChatModel 모델(String... 조각들) {
		return new 대역_모델(prompt -> Flux.fromArray(조각들).map(ChatPersistenceTest::응답));
	}

	private static ChatModel 고장난_모델() {
		return new 대역_모델(prompt -> Flux.error(new IllegalStateException("게이트웨이 다운")));
	}

	private static ChatResponse 응답(String delta) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
	}

	private record 대역_모델(java.util.function.Function<Prompt, Flux<ChatResponse>> 스트림) implements ChatModel {
		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("스트리밍만 쓴다");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return 스트림.apply(prompt);
		}
	}

	private record 저장된_메시지(Message message, String sourcesJson) {}

	private static final class 이력_대역 implements ChatHistoryRepository {
		final List<저장된_메시지> 저장된 = new CopyOnWriteArrayList<>();
		final List<String> 실행_스레드 = new CopyOnWriteArrayList<>();
		final CountDownLatch 저장_완료 = new CountDownLatch(2);
		volatile CountDownLatch 멈춤;
		volatile boolean 실패한다;

		@Override
		public UUID createSession(UUID userId, String title) {
			return UUID.randomUUID();
		}

		@Override
		public void deleteSession(UUID sessionId) {}

		@Override
		public List<Message> history(UUID sessionId) {
			return List.of();
		}

		@Override
		public List<com.llmhub.chat.SessionSummary> sessionsOf(UUID userId) {
			return List.of();
		}

		@Override
		public boolean isOwnedBy(UUID sessionId, UUID userId) {
			return true;
		}

		@Override
		public void append(UUID sessionId, Message message, String sourcesJson) {
			if (멈춤 != null) {
				try {
					멈춤.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (실패한다) {
				throw new IllegalStateException("DB 다운");
			}
			실행_스레드.add(Thread.currentThread().getName());
			저장된.add(new 저장된_메시지(message, sourcesJson));
			저장_완료.countDown();
		}
	}

	private static final class 감사_대역 implements AuditLogRepository {
		final List<AuditRecord> 기록된 = new CopyOnWriteArrayList<>();
		final List<String> 실행_스레드 = new CopyOnWriteArrayList<>();
		final CountDownLatch 기록_완료 = new CountDownLatch(1);
		volatile boolean 실패한다;

		@Override
		public void record(AuditRecord record) {
			if (실패한다) {
				throw new IllegalStateException("감사 DB 다운");
			}
			실행_스레드.add(Thread.currentThread().getName());
			기록된.add(record);
			기록_완료.countDown();
		}
	}

	private static final class 대역_임베딩 implements EmbeddingClient {
		@Override
		public EmbeddingSpec spec() {
			return new EmbeddingSpec("stub", 3);
		}

		@Override
		public List<float[]> embed(List<String> texts) {
			return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f}).toList();
		}
	}
}
