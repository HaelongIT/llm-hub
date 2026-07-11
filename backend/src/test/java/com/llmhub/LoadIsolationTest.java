package com.llmhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.audit.AuditLogRepository;
import com.llmhub.audit.AuditRecord;
import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.Message;
import com.llmhub.chat.SessionSummary;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.search.ChunkSearchRepository;
import com.llmhub.search.Source;
import com.llmhub.support.PostgresInitializer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * docs/00 v0 체크리스트: <b>부하 시에도 블로킹 DB 접근이 논블로킹 스트리밍 흐름을 막지 않는다.</b>
 *
 * <p>격리를 구조로 확인하는 것("boundedElastic 스레드에서 실행됐다")과 부하에서 확인하는 것은 다르다. 이벤트 루프가
 * 막히면 동시 요청이 <b>직렬화</b>되어 벽시계 시간이 폭발한다. 그것을 측정한다.
 *
 * <p>측정 원리: 블로킹 검색을 {@value #BLOCKING_MILLIS}ms 걸리게 만들고 {@value #CONCURRENCY}개를 동시에
 * 던진다. 직렬이면 최소 {@code CONCURRENCY × BLOCKING_MILLIS}가 걸린다. 격리되어 있으면 그 몇 분의 일이다.
 *
 * <p>BlockHound를 무장한 채 돌려, 이벤트 루프에서 블로킹이 일어나면 요청 자체가 실패하게 한다 (S13, PERF-1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@Import(LoadIsolationTest.대역.class)
class LoadIsolationTest {

	/**
	 * 동시 요청 수. boundedElastic 상한보다 작아야 큐잉이 결과를 흐리지 않는다. 기본 상한은 코어 × 10이라
	 * 4코어 미만 CI에서는 32보다 작아져 부분 직렬화로 플래키했다. build.gradle이
	 * {@code reactor.schedulers.defaultBoundedElasticSize=64}로 상한을 코어 수와 무관하게 고정한다.
	 */
	private static final int CONCURRENCY = 32;

	/** 검색 한 번의 블로킹 시간. 직렬화되면 즉시 드러날 만큼 크게 잡는다. */
	private static final int BLOCKING_MILLIS = 200;

	/** 직렬 실행이라면 최소 이만큼 걸린다. */
	private static final long SERIAL_FLOOR_MILLIS = (long) CONCURRENCY * BLOCKING_MILLIS;

	/** 넉넉한 상한. 이걸 넘으면 오버랩이 무너진 것이다. */
	private static final Duration PARALLEL_BUDGET = Duration.ofMillis(SERIAL_FLOOR_MILLIS / 4);

	private static final String USER_토큰 = "user";

	@LocalServerPort private int port;

	@BeforeAll
	static void 무장한다() {
		// 이벤트 루프에서 블로킹이 일어나면 BlockingOperationError로 요청이 깨진다.
		BlockHound.install();
	}

	@Test
	@DisplayName("동시 요청 32개가 직렬화되지 않고 겹쳐서 처리된다")
	void 부하에서도_스트리밍이_직렬화되지_않는다() {
		WebClient client = WebClient.create("http://localhost:" + port);

		// 워밍업. JIT·Jackson 캐시·커넥션 풀·Hibernate 첫 삽입이 측정에 섞이면 무엇을 재는지 알 수 없다.
		Flux.range(0, 4).flatMap(i -> 질문한다(client, i), 4).blockLast(Duration.ofSeconds(30));

		Instant 시작 = Instant.now();
		List<String> 응답들 =
				Flux.range(0, CONCURRENCY)
						.flatMap(i -> 질문한다(client, i), CONCURRENCY)
						.collectList()
						.block(Duration.ofSeconds(60));
		Duration 소요 = Duration.between(시작, Instant.now());

		// 측정값을 남긴다. 통과/실패만으로는 여유가 얼마나 남았는지 알 수 없다.
		System.out.printf(
				"[부하 격리] 동시 %d개 · 요청당 블로킹 %dms · 직렬 하한 %dms · 실제 %dms (오버랩 배율 %.1fx)%n",
				CONCURRENCY, BLOCKING_MILLIS, SERIAL_FLOOR_MILLIS, 소요.toMillis(), SERIAL_FLOOR_MILLIS / (double) 소요.toMillis());

		assertThat(응답들).hasSize(CONCURRENCY);
		assertThat(응답들)
				.as("모든 스트림이 done으로 깨끗이 끝난다. BlockHound가 이벤트 루프 블로킹을 잡았다면 여기서 깨진다")
				.allSatisfy(body -> assertThat(body).contains("event:sources").contains("event:done"));

		assertThat(소요)
				.as(
						"직렬이면 최소 %dms 걸린다. 실제 %dms. 이벤트 루프가 블로킹 검색에 잡혀 있으면 요청이 줄을 선다 (S13, PERF-1)",
						SERIAL_FLOOR_MILLIS, 소요.toMillis())
				.isLessThan(PARALLEL_BUDGET);
	}

	@Test
	@DisplayName("부하 중에도 블로킹 호출은 이벤트 루프 밖에서만 실행된다")
	void 블로킹은_이벤트_루프_밖에서만_돈다() throws InterruptedException {
		WebClient client = WebClient.create("http://localhost:" + port);
		대역.검색_스레드.clear();
		대역.저장_스레드.clear();
		대역.저장_완료 = new CountDownLatch(CONCURRENCY);

		Flux.range(0, CONCURRENCY).flatMap(i -> 질문한다(client, i), CONCURRENCY).blockLast(Duration.ofSeconds(60));
		대역.저장_완료.await(30, TimeUnit.SECONDS);

		assertThat(대역.검색_스레드).isNotEmpty();
		assertThat(대역.검색_스레드)
				.as("블로킹 검색이 Netty 이벤트 루프를 점유하면 스트리밍이 굶는다")
				.allSatisfy(name -> assertThat(name).startsWith("boundedElastic-"));
		assertThat(대역.저장_스레드)
				.as("스트림 완료 후의 이력·감사 저장도 격리된다 (S13)")
				.isNotEmpty()
				.allSatisfy(name -> assertThat(name).startsWith("boundedElastic-"));

		assertThat(대역.검색_스레드)
				.as("이벤트 루프 스레드 이름이 하나라도 섞이면 안 된다")
				.noneMatch(name -> name.contains("reactor-http") || name.contains("nio"));
	}

	private Mono<String> 질문한다(WebClient client, int i) {
		return client
				.post()
				.uri("/api/chat/stream")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_토큰)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("question", "연차휴가 문의 " + i))
				.retrieve()
				.bodyToMono(String.class);
	}

	@TestConfiguration
	static class 대역 {

		static final Set<String> 검색_스레드 = new CopyOnWriteArraySet<>();
		static final Set<String> 저장_스레드 = new CopyOnWriteArraySet<>();
		static volatile CountDownLatch 저장_완료 = new CountDownLatch(1);

		/** 블로킹 검색. 실제 ES 호출을 대신해 정확히 재현 가능한 지연을 만든다. */
		@Bean
		@Primary
		ChunkSearchRepository 느린_검색() {
			return (queryText, queryVector, accessTags, topK) -> {
				검색_스레드.add(Thread.currentThread().getName());
				블로킹한다(BLOCKING_MILLIS);
				return List.of(new Source("doc-1", "규정.txt", "0", "연차휴가는 15일이다.", 1.5));
			};
		}

		@Bean
		@Primary
		ChatHistoryRepository 느린_이력() {
			return new ChatHistoryRepository() {
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
				public List<SessionSummary> sessionsOf(UUID userId) {
					return List.of();
				}

				@Override
				public boolean isOwnedBy(UUID sessionId, UUID userId) {
					return true;
				}

				@Override
				public void append(UUID sessionId, Message message, String sourcesJson) {
					저장_스레드.add(Thread.currentThread().getName());
					블로킹한다(50);
				}

				@Override
				public void appendTurn(UUID sessionId, Message user, Message assistant, String sourcesJson) {
					append(sessionId, user, null);
					append(sessionId, assistant, sourcesJson);
				}
			};
		}

		@Bean
		@Primary
		AuditLogRepository 느린_감사() {
			return (AuditRecord record) -> {
				저장_스레드.add(Thread.currentThread().getName());
				블로킹한다(50);
				저장_완료.countDown();
			};
		}

		/**
		 * BlockHound가 잡는 블로킹 호출이어야 한다. JDK 21에서 {@code Thread.sleep}은 더 이상 잡히지 않으므로
		 * 파일 읽기로 블로킹한다 — 실제 DB·ES 호출이 하는 일과 같은 종류다.
		 */
		private static void 블로킹한다(int millis) {
			// 파일 읽기가 BlockHound의 탐지 대상이다. 이벤트 루프에서 돌면 여기서 터진다.
			try (var in = new java.io.FileInputStream(임시)) {
				in.read();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			// 지연 자체는 대기로 만든다. 바쁜 루프는 CPU를 태워 측정을 흐린다.
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private static final java.io.File 임시 = 만든다();

		private static java.io.File 만든다() {
			try {
				java.io.File f = java.io.File.createTempFile("load", ".txt");
				f.deleteOnExit();
				java.nio.file.Files.writeString(f.toPath(), "x");
				return f;
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
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
					return Flux.just("답변입니다.")
							.map(d -> new ChatResponse(List.of(new Generation(new AssistantMessage(d)))));
				}
			};
		}

		@Bean
		@Primary
		ReactiveJwtDecoder 대역_디코더() {
			return token -> {
				if (!USER_토큰.equals(token)) {
					return Mono.error(new BadJwtException("무효"));
				}
				Instant now = Instant.now();
				return Mono.just(
						Jwt.withTokenValue("t")
								.header("alg", "RS256")
								.subject("load-test-user")
								.issuedAt(now)
								.expiresAt(now.plusSeconds(3600))
								.claim("realm_access", Map.of("roles", List.of("USER")))
								.build());
			};
		}
	}
}
