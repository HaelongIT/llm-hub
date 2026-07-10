package com.llmhub.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.search.ChunkSearchRepository;
import com.llmhub.search.Source;
import com.llmhub.support.PostgresInitializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REQ-AUTH 시나리오: <b>태그 확정이 검색 호출보다 먼저 일어난다.</b>
 *
 * <p>인증되지 않은 요청은 SEARCH에 닿지 않는다. 앞단 게이트가 필터체인에 있으므로 컨트롤러가 실행되기 전에 막힌다 —
 * 컨트롤러가 권한을 확인하는 구조라면 새 엔드포인트가 그것을 빼먹을 수 있다 (S4, SEC-2).
 *
 * <p>REQ-CHAT: 이벤트 타입 SSE로 {@code sources}/{@code text}/{@code done}이 흐른다 (S6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@Import(ChatControllerTest.대역.class)
class ChatControllerTest {

	private static final String USER_토큰 = "user";
	private static final String ADMIN_토큰 = "admin";

	@Autowired private WebTestClient client;
	@Autowired private 검색_감시자 검색;

	@BeforeEach
	void 감시자를_비운다() {
		검색.호출된_태그.clear();
	}

	@Test
	@DisplayName("USER가 질문하면 게이트가 확정한 {public} 태그로 검색된다")
	void USER의_태그로_검색된다() {
		질문한다(USER_토큰).expectStatus().isOk();

		assertThat(검색.호출된_태그)
				.as("검색 계층은 태그를 재계산하지 않는다. 게이트가 확정한 것을 소비만 한다 (S4)")
				.containsExactly(Set.of("public"));
	}

	@Test
	@DisplayName("ADMIN이 질문하면 {public, restricted} 태그로 검색된다")
	void ADMIN의_태그로_검색된다() {
		질문한다(ADMIN_토큰).expectStatus().isOk();

		assertThat(검색.호출된_태그).containsExactly(Set.of("public", "restricted"));
	}

	@Test
	@DisplayName("인증되지 않은 요청은 검색에 닿지도 못한다")
	void 인증_없는_요청은_검색에_닿지_않는다() {
		client
				.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("question", "연차휴가는?"))
				.exchange()
				.expectStatus()
				.isUnauthorized();

		assertThat(검색.호출된_태그)
				.as("태그 확정이 검색보다 먼저다. 게이트를 통과하지 못한 요청은 하위 계층에 도달하지 않는다 (REQ-AUTH 불변식)")
				.isEmpty();
	}

	@Test
	@DisplayName("응답이 sources → text → done 순서의 이벤트 SSE로 흐른다")
	void 이벤트_타입_SSE로_흐른다() {
		String body = 질문한다(USER_토큰).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).isNotNull();
		int sources = body.indexOf("event:sources");
		int text = body.indexOf("event:text");
		int done = body.indexOf("event:done");

		assertThat(sources).as("근거가 먼저 나간다 (S6)").isGreaterThanOrEqualTo(0).isLessThan(text);
		assertThat(text).isLessThan(done);
		assertThat(body).contains("연차휴가는 15일이다.");
	}

	private WebTestClient.ResponseSpec 질문한다(String token) {
		return client
				.post()
				.uri("/api/chat/stream")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("question", "연차휴가는?"))
				.exchange();
	}

	@TestConfiguration
	static class 대역 {

		@Bean
		검색_감시자 검색_감시자() {
			return new 검색_감시자();
		}

		@Bean
		@Primary
		ChunkSearchRepository 대역_검색저장소(검색_감시자 감시자) {
			return 감시자;
		}

		@Bean
		@Primary
		EmbeddingClient 대역_임베딩() {
			return new EmbeddingClient() {
				@Override
				public EmbeddingSpec spec() {
					return new EmbeddingSpec("stub", 3);
				}

				@Override
				public List<float[]> embed(List<String> texts) {
					return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f}).toList();
				}
			};
		}

		@Bean
		@Primary
		ChatModel 대역_모델() {
			return new ChatModel() {
				@Override
				public ChatResponse call(Prompt prompt) {
					throw new UnsupportedOperationException("스트리밍만 쓴다");
				}

				@Override
				public Flux<ChatResponse> stream(Prompt prompt) {
					return Flux.just("연차휴가는 ", "15일입니다.")
							.map(d -> new ChatResponse(List.of(new Generation(new AssistantMessage(d)))));
				}
			};
		}

		@Bean
		@Primary
		ReactiveJwtDecoder 대역_디코더() {
			return token ->
					switch (token) {
						case USER_토큰 -> Mono.just(jwt("USER"));
						case ADMIN_토큰 -> Mono.just(jwt("ADMIN"));
						default -> Mono.error(new BadJwtException("무효"));
					};
		}

		private static Jwt jwt(String role) {
			Instant now = Instant.now();
			return Jwt.withTokenValue("t")
					.header("alg", "RS256")
					.subject("subject-" + role)
					.issuedAt(now)
					.expiresAt(now.plusSeconds(3600))
					.claim("realm_access", Map.of("roles", List.of(role)))
					.build();
		}
	}

	/** 검색이 어떤 태그로 불렸는지 기록한다. 불리지 않았다면 비어 있다. */
	static class 검색_감시자 implements ChunkSearchRepository {
		final List<Set<String>> 호출된_태그 = new CopyOnWriteArrayList<>();

		@Override
		public List<Source> search(String queryText, float[] queryVector, Set<String> accessTags, int topK) {
			호출된_태그.add(Set.copyOf(accessTags));
			return List.of(new Source("doc-1", "규정.txt", "0", "연차휴가는 15일이다.", 1.5));
		}
	}
}
