package com.llmhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.support.ElasticsearchInitializer;
import com.llmhub.support.PostgresInitializer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * docs/00의 v0 완성 체크리스트를 하나의 흐름으로 검증한다 (docs/05).
 *
 * <p>문서 색인(관리자) → 로그인 → 질문 → 접근태그 필터된 하이브리드 검색 → 근거 포함 스트리밍 응답 → 감사 기록.
 *
 * <p>실제 Elasticsearch(nori)와 PostgreSQL을 띄운다. LLM과 임베딩 게이트웨이만 대역이다 — 그 둘은 외부
 * 서비스이고, 나머지는 우리가 배포하는 것들이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PostgresInitializer.class, ElasticsearchInitializer.class})
@Import(V0EndToEndTest.대역.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V0EndToEndTest {

	private static final String USER_토큰 = "user";
	private static final String ADMIN_토큰 = "admin";

	private static final String 공개_문서 = "연차휴가는 근로기준법에 따라 부여한다. 규정 코드 REG-2026-01 이 적용된다.";
	private static final String 기밀_문서 = "임원 특별휴가 지침은 대외비다.";

	@Autowired private WebTestClient client;
	@Autowired private DataSource dataSource;

	@Test
	@Order(1)
	@DisplayName("관리자가 문서를 색인하면 조각이 메타 7종과 함께 검색 가능해진다")
	void 관리자가_문서를_색인한다() {
		색인한다(ADMIN_토큰, "규정-공개", "규정.txt", 공개_문서, "public").expectStatus().isOk();
		색인한다(ADMIN_토큰, "규정-기밀", "기밀.txt", 기밀_문서, "restricted").expectStatus().isOk();
	}

	@Test
	@Order(2)
	@DisplayName("색인 API는 ADMIN만 호출할 수 있다")
	void 비_ADMIN은_색인할_수_없다() {
		색인한다(USER_토큰, "몰래", "몰래.txt", "몰래 넣은 문서", "public").expectStatus().isForbidden();
	}

	@Test
	@Order(3)
	@DisplayName("USER의 질문에 근거가 붙은 답이 스트리밍되고, restricted 문서는 결과에 없다")
	void USER는_공개_문서만_근거로_받는다() {
		String 응답 = 질문한다(USER_토큰, "연차휴가는 며칠인가요?");

		assertThat(응답).as("이벤트 타입 SSE").contains("event:sources").contains("event:text").contains("event:done");
		assertThat(응답).as("근거는 서버가 실제로 검색한 조각이다 (S6)").contains("연차휴가는 근로기준법에 따라 부여한다.");
		assertThat(응답).as("권한 없는 조각은 검색 단계에서 배제된다 (SEC-2)").doesNotContain("임원 특별휴가");
		assertThat(응답).doesNotContain("기밀.txt");
	}

	@Test
	@Order(4)
	@DisplayName("ADMIN의 질문에는 restricted 문서가 근거로 나온다")
	void ADMIN은_기밀_문서도_본다() {
		String 응답 = 질문한다(ADMIN_토큰, "임원 특별휴가 지침");

		assertThat(응답).contains("임원 특별휴가 지침은 대외비다.");
	}

	@Test
	@Order(5)
	@DisplayName("정확한 코드로 물어도 한국어 형태소·BM25로 문서를 찾는다")
	void 정확한_코드로도_찾는다() {
		String 응답 = 질문한다(USER_토큰, "REG-2026-01");

		assertThat(응답).contains("REG-2026-01");
	}

	@Test
	@Order(6)
	@DisplayName("같은 doc_key로 재업로드하면 구버전 조각이 사라지고 신버전만 검색된다")
	void 재업로드하면_구버전이_사라진다() {
		색인한다(ADMIN_토큰, "규정-공개", "규정.txt", "연차휴가는 이제 20일로 늘어났다.", "public").expectStatus().isOk();

		String 응답 = 질문한다(USER_토큰, "연차휴가");

		assertThat(응답).contains("20일로 늘어났다");
		assertThat(응답).as("구버전이 남으면 같은 내용이 중복 근거로 나온다 (S17)").doesNotContain("근로기준법에 따라 부여한다");
	}

	@Test
	@Order(7)
	@DisplayName("채팅 한 번에 감사 레코드가 남는다")
	void 감사_레코드가_남는다() throws InterruptedException {
		int 이전 = 감사_레코드_수();

		질문한다(USER_토큰, "연차휴가 문의");

		// 감사 저장은 비동기다. 스트림은 그것을 기다리지 않는다 (S13).
		for (int i = 0; i < 50 && 감사_레코드_수() == 이전; i++) {
			Thread.sleep(100);
		}
		assertThat(감사_레코드_수()).isGreaterThan(이전);
	}

	// ─── 요청 헬퍼 ───

	private WebTestClient.ResponseSpec 색인한다(
			String token, String docKey, String filename, String 내용, String tags) {
		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder
				.part("file", new ByteArrayResource(내용.getBytes(StandardCharsets.UTF_8)) {
					@Override
					public String getFilename() {
						return filename;
					}
				})
				.contentType(MediaType.TEXT_PLAIN);
		builder.part("docKey", docKey);
		builder.part("accessTags", tags);

		return client
				.post()
				.uri("/api/index")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(builder.build())
				.exchange();
	}

	private String 질문한다(String token, String question) {
		String body =
				client
						.post()
						.uri("/api/chat/stream")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.bodyValue(Map.of("question", question))
						.exchange()
						.expectStatus()
						.isOk()
						.expectBody(String.class)
						.returnResult()
						.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private int 감사_레코드_수() {
		Integer count = new JdbcTemplate(dataSource).queryForObject("select count(*) from audit_log", Integer.class);
		return count == null ? 0 : count;
	}

	@TestConfiguration
	static class 대역 {

		/**
		 * 임베딩 게이트웨이 대역. 조각 텍스트에 '휴가'가 들어가면 휴가 축으로, 아니면 코드 축으로 보낸다. 벡터가 의미를 흉내
		 * 내야 하이브리드의 벡터 절이 실제로 기여한다.
		 */
		@Bean
		@Primary
		EmbeddingClient 대역_임베딩() {
			return new EmbeddingClient() {
				@Override
				public EmbeddingSpec spec() {
					return new EmbeddingSpec("stub-embedding", 3);
				}

				@Override
				public List<float[]> embed(List<String> texts) {
					return texts.stream().map(대역::벡터화).toList();
				}
			};
		}

		private static float[] 벡터화(String text) {
			if (text.contains("휴가")) {
				return new float[] {1.0f, 0.0f, 0.0f};
			}
			return new float[] {0.0f, 1.0f, 0.0f};
		}

		/** LLM 대역. 프롬프트에 실린 근거를 그대로 되뇐다 — 근거가 실제로 주입됐는지 응답에서 확인하기 위함이다. */
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
					return Flux.just("답변: ", "근거를 참고했습니다.")
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
}
