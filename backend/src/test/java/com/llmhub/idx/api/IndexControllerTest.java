package com.llmhub.idx.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.idx.index.ChunkRepository;
import com.llmhub.idx.index.EmbeddedChunk;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.support.PostgresInitializer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * REQ-IDX 시나리오: 비-ADMIN 업로드 시도 → 거부(403). (OQ-004)
 *
 * <p>SEC-2: 색인 API는 ADMIN 역할만. SEC-1: 인증되지 않은 요청은 401.
 *
 * <p>S13: 색인은 블로킹이다. 논블로킹 컨트롤러가 격리 스케줄러로 넘겨야 이벤트 루프가 굶지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@Import(IndexControllerTest.대역.class)
class IndexControllerTest {

	private static final String USER_토큰 = "user";
	private static final String ADMIN_토큰 = "admin";

	@Autowired private WebTestClient client;
	@Autowired private 조각_수집기 chunks;
	@Autowired private com.llmhub.idx.persistence.DocumentJpaRepository documents;

	/** 수집기는 Spring 컨텍스트 싱글턴이다. 비우지 않으면 앞 테스트의 조각이 남아 단언을 오염시킨다. */
	@org.junit.jupiter.api.BeforeEach
	void 수집기를_비운다() {
		chunks.저장된.clear();
		chunks.실행_스레드.clear();
	}

	@Test
	@DisplayName("ADMIN은 문서를 색인할 수 있다")
	void ADMIN은_색인할_수_있다() {
		업로드(ADMIN_토큰).expectStatus().isOk();

		assertThat(chunks.저장된).isNotEmpty();
		assertThat(chunks.실행_스레드)
				.as("블로킹 색인이 Netty 이벤트 루프를 점유하면 논블로킹 스트리밍이 굶는다 (S13)")
				.allSatisfy(name -> assertThat(name).startsWith("boundedElastic-"));
	}

	@Test
	@DisplayName("USER가 색인을 시도하면 403이다")
	void USER는_403이다() {
		업로드(USER_토큰).expectStatus().isForbidden();

		assertThat(chunks.저장된).as("거부된 업로드는 아무것도 색인하지 않는다").isEmpty();
	}

	@Test
	@DisplayName("허용되지 않은 확장자는 400이다 — 서버 장애가 아니라 클라이언트 잘못이다")
	void 허용되지_않은_확장자는_400이다() {
		// MIME 불일치도 같은 예외(UploadRejectedException)를 던지므로 같은 400이 된다.
		// 그쪽은 여기서 재현할 수 없다 — ResourceHttpMessageWriter가 파일명에서 Content-Type을
		// 다시 유도해 버려서 이 대역으로는 MIME을 속일 수 없다. 실제 클라이언트는 속일 수 있고,
		// 그 경로는 UploadValidatorTest가 단위로 막는다.
		업로드(ADMIN_토큰, "악성.exe", MediaType.APPLICATION_OCTET_STREAM).expectStatus().isBadRequest();

		assertThat(chunks.저장된).as("거부된 업로드는 아무것도 색인하지 않는다 (SEC-4)").isEmpty();
	}

	@Test
	@DisplayName("인증 없이 색인을 시도하면 401이다")
	void 인증이_없으면_401이다() {
		client
				.post()
				.uri("/api/index")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(멀티파트())
				.exchange()
				.expectStatus()
				.isUnauthorized();

		assertThat(chunks.저장된).isEmpty();
	}

	// REQ-IDX 시나리오: 비-ADMIN의 재색인·대상조회 시도 → 거부(403).
	//
	// 인가는 경로 패턴으로 걸려야 한다. 엔드포인트가 아직 없어도 마찬가지다 — 핸들러가
	// 생기는 순간 열리는 구조라면 그것은 인가가 아니다 (SEC-2, S4).

	@Test
	@DisplayName("USER가 재색인을 시도하면 403이다")
	void USER는_재색인할_수_없다() {
		재색인(USER_토큰, "규정-2026").expectStatus().isForbidden();
	}

	@Test
	@DisplayName("USER가 재색인 대상 조회를 시도하면 403이다")
	void USER는_재색인_대상을_조회할_수_없다() {
		client
				.get()
				.uri("/api/index/stale")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_토큰)
				.exchange()
				.expectStatus()
				.isForbidden();
	}

	@Test
	@DisplayName("인증 없이 재색인을 시도하면 401이다")
	void 인증이_없으면_재색인도_401이다() {
		client
				.post()
				.uri(b -> b.path("/api/index/reindex").queryParam("docKey", "규정-2026").build())
				.exchange()
				.expectStatus()
				.isUnauthorized();
	}

	@Test
	@DisplayName("ADMIN은 업로드한 문서를 원본에서 재색인할 수 있다")
	void ADMIN은_재색인할_수_있다() {
		업로드(ADMIN_토큰).expectStatus().isOk();
		chunks.저장된.clear();

		재색인(ADMIN_토큰, "규정-2026").expectStatus().isOk().expectBody().jsonPath("$.chunkCount").isEqualTo(1);

		assertThat(chunks.저장된).as("재색인은 파일 없이 doc_key만으로 원본을 다시 읽는다 (S16)").isNotEmpty();
		assertThat(chunks.실행_스레드)
				.as("재색인도 블로킹이다. 이벤트 루프에서 돌면 안 된다 (S13)")
				.allSatisfy(name -> assertThat(name).startsWith("boundedElastic-"));
	}

	@Test
	@DisplayName("업로드한 관리자가 document에 기록되고, 재색인은 그것을 바꾸지 않는다")
	void 업로더가_기록되고_재색인이_바꾸지_않는다() {
		업로드(ADMIN_토큰).expectStatus().isOk();

		java.util.UUID 올린이 = 문서의_업로더();
		assertThat(올린이).as("restricted 문서를 누가 올렸는지 남는 유일한 자리다 (docs/03)").isNotNull();

		재색인(ADMIN_토큰, "규정-2026").expectStatus().isOk();

		assertThat(문서의_업로더()).as("재색인은 다시 올린 것이 아니다").isEqualTo(올린이);
	}

	private java.util.UUID 문서의_업로더() {
		return documents.findByDocKey("규정-2026").orElseThrow().getUploadedBy();
	}

	@Test
	@DisplayName("없는 doc_key로 재색인하면 404다")
	void 없는_문서는_404다() {
		재색인(ADMIN_토큰, "존재하지-않는-키").expectStatus().isNotFound();

		assertThat(chunks.저장된).isEmpty();
	}

	@Test
	@DisplayName("실패 응답의 본문 traceId가 응답 헤더의 추적 ID와 같다")
	void 실패해도_추적_ID로_이어진다() {
		// 실패한 요청이야말로 추적이 필요하다. 헤더와 본문이 다른 값을 주면 사용자가
		// 무엇을 신고해야 할지 알 수 없다 (REL-3).
		재색인(ADMIN_토큰, "존재하지-않는-키")
				.expectStatus()
				.isNotFound()
				.expectBody()
				.consumeWith(결과 -> {
					String 헤더 = 결과.getResponseHeaders().getFirst(com.llmhub.common.TraceId.HEADER);
					String 본문 = new String(결과.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
					assertThat(헤더).isNotBlank();
					assertThat(본문).contains("\"traceId\":\"" + 헤더 + "\"");
				});
	}

	@Test
	@DisplayName("ADMIN은 재색인 대상 목록을 조회할 수 있다")
	void ADMIN은_재색인_대상을_조회한다() {
		client
				.get()
				.uri("/api/index/stale")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_토큰)
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$")
				.isArray();
	}

	private WebTestClient.ResponseSpec 재색인(String token, String docKey) {
		return client
				.post()
				.uri(b -> b.path("/api/index/reindex").queryParam("docKey", docKey).build())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange();
	}

	private WebTestClient.ResponseSpec 업로드(String token) {
		return 업로드(token, "규정.txt", MediaType.TEXT_PLAIN);
	}

	private WebTestClient.ResponseSpec 업로드(String token, String filename, MediaType contentType) {
		return client
				.post()
				.uri("/api/index")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(멀티파트(filename, contentType))
				.exchange();
	}

	private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> 멀티파트() {
		return 멀티파트("규정.txt", MediaType.TEXT_PLAIN);
	}

	private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> 멀티파트(
			String filename, MediaType contentType) {
		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder
				.part("file", new ByteArrayResource("연차휴가는 15일이다.".getBytes(StandardCharsets.UTF_8)) {
					@Override
					public String getFilename() {
						return filename;
					}
				})
				.contentType(contentType);
		builder.part("docKey", "규정-2026");
		builder.part("accessTags", "public");
		return builder.build();
	}

	@TestConfiguration
	static class 대역 {

		@Bean
		조각_수집기 조각_수집기() {
			return new 조각_수집기();
		}

		@Bean
		@Primary
		EmbeddingClient 대역_임베딩() {
			EmbeddingSpec spec = new EmbeddingSpec("stub-embedding", 3);
			return new EmbeddingClient() {
				@Override
				public EmbeddingSpec spec() {
					return spec;
				}

				@Override
				public List<float[]> embed(List<String> texts) {
					return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f}).toList();
				}
			};
		}

		@Bean
		@Primary
		ChunkRepository 대역_조각저장소(조각_수집기 수집기) {
			return 수집기;
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

	/** 색인된 조각과 그것을 저장한 스레드 이름을 함께 모은다. */
	static class 조각_수집기 implements ChunkRepository {
		final List<EmbeddedChunk> 저장된 = new CopyOnWriteArrayList<>();
		final List<String> 실행_스레드 = new CopyOnWriteArrayList<>();

		@Override
		public java.util.OptionalInt indexedDimensions() {
			return java.util.OptionalInt.empty();
		}

		@Override
		public void createIndexIfMissing(EmbeddingSpec spec) {}

		@Override
		public void indexAll(List<EmbeddedChunk> batch) {
			실행_스레드.add(Thread.currentThread().getName());
			저장된.addAll(batch);
		}

		@Override
		public void deleteStaleChunks(String documentId, String currentIndexingRunId) {}

		@Override
		public List<IndexedChunk> findByDocumentId(String documentId) {
			return new ArrayList<>();
		}
	}
}
