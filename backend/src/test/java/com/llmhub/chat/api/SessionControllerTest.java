package com.llmhub.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.support.PostgresInitializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import reactor.core.publisher.Mono;

/**
 * REQ-CHAT: 세션은 사용자 소유다 — 생성·조회·삭제 (S2).
 *
 * <p>소유권은 데이터 수준의 불변식이다. 남의 세션을 지울 수 없어야 하고, 목록에도 보이면 안 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@Import(SessionControllerTest.대역.class)
class SessionControllerTest {

	private static final String 앨리스 = "alice";
	private static final String 밥 = "bob";

	@Autowired private WebTestClient client;

	@Test
	@DisplayName("세션을 만들면 내 목록에 나온다")
	void 세션을_만들고_조회한다() {
		UUID sessionId = 세션을_만든다(앨리스, "연차 문의");

		assertThat(세션_목록(앨리스)).extracting("id").contains(sessionId.toString());
	}

	@Test
	@DisplayName("다른 사용자의 세션은 내 목록에 없다")
	void 남의_세션은_보이지_않는다() {
		UUID 밥의_세션 = 세션을_만든다(밥, "밥의 대화");

		assertThat(세션_목록(앨리스))
				.as("세션은 사용자 소유다 (S2)")
				.extracting("id")
				.doesNotContain(밥의_세션.toString());
	}

	@Test
	@DisplayName("남의 세션은 지울 수 없다")
	void 남의_세션은_지울_수_없다() {
		UUID 밥의_세션 = 세션을_만든다(밥, "밥의 대화");

		client
				.delete()
				.uri("/api/sessions/" + 밥의_세션)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 앨리스)
				.exchange()
				.expectStatus()
				.isNotFound();

		assertThat(세션_목록(밥)).extracting("id").contains(밥의_세션.toString());
	}

	@Test
	@DisplayName("내 세션을 지우면 목록에서 사라진다")
	void 내_세션을_지운다() {
		UUID sessionId = 세션을_만든다(앨리스, "지울 대화");

		client
				.delete()
				.uri("/api/sessions/" + sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 앨리스)
				.exchange()
				.expectStatus()
				.isNoContent();

		assertThat(세션_목록(앨리스)).extracting("id").doesNotContain(sessionId.toString());
	}

	@Test
	@DisplayName("인증 없이는 세션에 접근할 수 없다")
	void 인증_없이는_접근할_수_없다() {
		client.get().uri("/api/sessions").exchange().expectStatus().isUnauthorized();
		client.post().uri("/api/sessions").exchange().expectStatus().isUnauthorized();
	}

	private UUID 세션을_만든다(String user, String title) {
		Map<?, ?> created =
				client
						.post()
						.uri("/api/sessions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON)
						.bodyValue(Map.of("title", title))
						.exchange()
						.expectStatus()
						.isOk()
						.expectBody(Map.class)
						.returnResult()
						.getResponseBody();
		assertThat(created).isNotNull();
		return UUID.fromString((String) created.get("id"));
	}

	private List<?> 세션_목록(String user) {
		return client
				.get()
				.uri("/api/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + user)
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(List.class)
				.returnResult()
				.getResponseBody();
	}

	@TestConfiguration
	static class 대역 {

		@Bean
		@Primary
		ReactiveJwtDecoder 대역_디코더() {
			return token ->
					switch (token) {
						case 앨리스, 밥 -> Mono.just(jwt(token));
						default -> Mono.error(new BadJwtException("무효"));
					};
		}

		private static Jwt jwt(String subject) {
			Instant now = Instant.now();
			return Jwt.withTokenValue("t")
					.header("alg", "RS256")
					.subject(subject)
					.issuedAt(now)
					.expiresAt(now.plusSeconds(3600))
					.claim("realm_access", Map.of("roles", List.of("USER")))
					.build();
		}
	}
}
