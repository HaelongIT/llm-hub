package com.llmhub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.search.SearchService;
import com.llmhub.search.Source;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
 * REQ-CHAT 시나리오: text 조각들이 순차 스트리밍되고 done으로 종료. sources 이벤트가 SEARCH 결과와 일치.
 * LLM 장애 → error 이벤트로 종료.
 *
 * <p><b>S6가 이 설계를 강제한다.</b> 근거는 서버 검색 결과에서만 나온다. 그래서 CHAT이 SEARCH를 명시적으로 먼저
 * 부르고, {@code sources}를 첫 {@code text} 토큰보다 <b>앞서</b> 확정 발행한다. 검색을 Advisor 안에
 * 숨기면 이 순서를 보장할 수 없다.
 *
 * <p>S13: 블로킹 검색은 격리 스케줄러에서 돈다.
 */
class ChatServiceTest {

	private static final List<Source> 검색결과 =
			List.of(
					new Source("doc-1", "규정.txt", "0", "연차휴가는 15일이다.", 1.5),
					new Source("doc-2", "규정.txt", "1", "휴가는 1년간 행사한다.", 1.2));

	private final AtomicReference<String> 검색_실행_스레드 = new AtomicReference<>();
	private final AtomicReference<Prompt> 받은_프롬프트 = new AtomicReference<>();

	private static final java.util.UUID 세션ID = java.util.UUID.randomUUID();
	private static final String 요청자 = "user-1";

	private ChatService 서비스(SearchService search, ChatModel model) {
		return new ChatService(
				search,
				ChatClient.builder(model).build(),
				new RecentTurnsContextAssembler(2, 100_000),
				저장하지_않는_이력(),
				record -> {},
				com.llmhub.audit.AuditScope.FULL);
	}

	/** 이 테스트는 스트리밍만 본다. 저장은 ChatPersistenceTest의 몫이다. */
	private static ChatHistoryRepository 저장하지_않는_이력() {
		return new ChatHistoryRepository() {
			@Override
			public java.util.UUID createSession(java.util.UUID userId, String title) {
				return java.util.UUID.randomUUID();
			}

			@Override
			public void deleteSession(java.util.UUID sessionId) {}

			@Override
			public List<Message> history(java.util.UUID sessionId) {
				return List.of();
			}

			@Override
			public void append(java.util.UUID sessionId, Message message, String sourcesJson) {}

			@Override
			public List<SessionSummary> sessionsOf(java.util.UUID userId) {
				return List.of();
			}

			@Override
			public boolean isOwnedBy(java.util.UUID sessionId, java.util.UUID userId) {
				return true;
			}
		};
	}

	private SearchService 검색_대역(List<Source> 결과) {
		return new SearchService(q -> q, new 대역_임베딩(), (t, v, tags, k) -> {
			검색_실행_스레드.set(Thread.currentThread().getName());
			return 결과;
		}, 5);
	}

	private ChatModel 텍스트를_흘리는_모델(String... 조각들) {
		return new 대역_모델(prompt -> {
			받은_프롬프트.set(prompt);
			return Flux.fromArray(조각들).map(ChatServiceTest::응답);
		});
	}

	@Test
	@DisplayName("sources가 첫 text보다 먼저 나가고 done으로 끝난다")
	void 이벤트_순서가_sources_text_done이다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("연차", "휴가는 ", "15일입니다."));

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(events).isNotNull();
		assertThat(events.get(0))
				.as("근거를 첫 토큰보다 먼저 확정 발행한다. Advisor 안에 검색을 숨기면 보장할 수 없다 (S6)")
				.isInstanceOf(ChatEvent.Sources.class);
		assertThat(events.subList(1, events.size() - 1)).allSatisfy(e -> assertThat(e).isInstanceOf(ChatEvent.Text.class));
		assertThat(events.get(events.size() - 1)).isEqualTo(new ChatEvent.Done("trace-1"));
	}

	@Test
	@DisplayName("sources 이벤트의 내용이 SEARCH 결과와 일치한다")
	void sources가_검색_결과와_일치한다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("답"));

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(((ChatEvent.Sources) events.get(0)).sources())
				.as("CHAT은 근거를 새로 만들지 않는다. SEARCH가 만든 것을 그대로 전달한다")
				.isEqualTo(검색결과);
	}

	@Test
	@DisplayName("text 조각이 순차로 흘러나온다")
	void text_조각이_순차로_흐른다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("연차", "휴가는 ", "15일입니다."));

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(events)
				.filteredOn(ChatEvent.Text.class::isInstance)
				.extracting(e -> ((ChatEvent.Text) e).delta())
				.containsExactly("연차", "휴가는 ", "15일입니다.");
	}

	@Test
	@DisplayName("LLM 장애면 error 이벤트로 스트림이 깨끗이 닫힌다")
	void LLM_장애는_error_이벤트로_끝난다() {
		ChatModel 고장난_모델 = new 대역_모델(prompt -> Flux.error(new IllegalStateException("게이트웨이 다운")));
		ChatService service = 서비스(검색_대역(검색결과), 고장난_모델);

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(events).last().isInstanceOf(ChatEvent.Error.class);
		assertThat(events).noneMatch(ChatEvent.Done.class::isInstance);
		assertThat(events)
				.as("부분 응답 후 끊김을 사용자가 구분할 수 있어야 한다 (REL-1)")
				.first()
				.isInstanceOf(ChatEvent.Sources.class);
	}

	@Test
	@DisplayName("검색 장애도 error 이벤트로 끝난다. 무한 대기가 없다")
	void 검색_장애도_error_이벤트다() {
		SearchService 고장난_검색 =
				new SearchService(q -> q, new 대역_임베딩(), (t, v, tags, k) -> {
					throw new IllegalStateException("ES 다운");
				}, 5);
		ChatService service = 서비스(고장난_검색, 텍스트를_흘리는_모델("답"));

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		assertThat(events).singleElement().isInstanceOf(ChatEvent.Error.class);
	}

	@Test
	@DisplayName("error 이벤트는 내부 예외 문구를 싣지 않고 추적 ID를 싣는다")
	void error_이벤트가_내부_예외를_흘리지_않는다() {
		// HTTP 에러 본문에서는 예외 메시지를 막아뒀다(server.error.include-message를 켜지 않는다).
		// SSE error 이벤트만 열려 있으면 ES·게이트웨이의 내부 문구가 브라우저로 나간다.
		ChatModel 고장난_모델 =
				new 대역_모델(prompt -> Flux.error(new IllegalStateException("ES 인덱스 llmhub-chunks 접근 거부: user=elastic")));
		ChatService service = 서비스(검색_대역(검색결과), 고장난_모델);

		List<ChatEvent> events =
				service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").collectList().block();

		ChatEvent.Error 오류 = (ChatEvent.Error) events.get(events.size() - 1);
		assertThat(오류.message())
				.as("내부 문구는 로그에만 남는다. 사용자에게는 추적 ID를 준다 (SEC-3)")
				.doesNotContain("llmhub-chunks")
				.doesNotContain("elastic")
				.contains("trace-1");
	}

	@Test
	@DisplayName("블로킹 검색은 격리 스케줄러에서 실행된다")
	void 검색은_격리_스케줄러에서_돈다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("답"));

		service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").blockLast();

		assertThat(검색_실행_스레드.get())
				.as("블로킹 ES 호출이 이벤트 루프를 점유하면 스트리밍이 굶는다 (S13)")
				.startsWith("boundedElastic-");
	}

	@Test
	@DisplayName("최근 N턴 이력이 프롬프트에 실린다")
	void 이력이_프롬프트에_실린다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("답"));
		List<Message> 이력 =
				List.of(
						Message.user("오래된 질문"),
						Message.assistant("오래된 답"),
						Message.user("최근 질문"),
						Message.assistant("최근 답"),
						Message.user("더 최근 질문"),
						Message.assistant("더 최근 답"));

		service.stream(세션ID, 요청자, "후속 질문", Set.of("public"), 이력, "trace-1").blockLast();

		String 프롬프트 = 받은_프롬프트.get().getContents();
		assertThat(프롬프트).contains("더 최근 질문").contains("후속 질문");
		assertThat(프롬프트).as("N턴(2턴=4메시지)을 넘는 오래된 이력은 빠진다 (S2, PERF-5)").doesNotContain("오래된 질문");
	}

	@Test
	@DisplayName("검색된 근거가 프롬프트에 주입된다")
	void 근거가_프롬프트에_주입된다() {
		ChatService service = 서비스(검색_대역(검색결과), 텍스트를_흘리는_모델("답"));

		service.stream(세션ID, 요청자, "연차휴가는?", Set.of("public"), List.of(), "trace-1").blockLast();

		assertThat(받은_프롬프트.get().getContents())
				.as("근거 없이 답하면 LLM이 지어낸다. 서버가 검색한 조각을 넣는다 (S6)")
				.contains("연차휴가는 15일이다.");
	}

	/** ChatModel은 call과 stream을 모두 가져 람다로 만들 수 없다. */
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

	private static ChatResponse 응답(String delta) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
	}

	private static final class 대역_임베딩 implements com.llmhub.common.embedding.EmbeddingClient {
		@Override
		public com.llmhub.common.embedding.EmbeddingSpec spec() {
			return new com.llmhub.common.embedding.EmbeddingSpec("stub", 3);
		}

		@Override
		public List<float[]> embed(List<String> texts) {
			return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f}).toList();
		}
	}
}
