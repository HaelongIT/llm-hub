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

	private WebTestClient.ResponseSpec 업로드(String token) {
		return client
				.post()
				.uri("/api/index")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(멀티파트())
				.exchange();
	}

	private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> 멀티파트() {
		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder
				.part("file", new ByteArrayResource("연차휴가는 15일이다.".getBytes(StandardCharsets.UTF_8)) {
					@Override
					public String getFilename() {
						return "규정.txt";
					}
				})
				.contentType(MediaType.TEXT_PLAIN);
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
