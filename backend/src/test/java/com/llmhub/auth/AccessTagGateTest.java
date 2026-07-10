package com.llmhub.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REQ-AUTH 시나리오: 유효 JWT → 인증 성공 + 태그가 컨텍스트에 실림. 무효/만료 JWT → 401.
 *
 * <p>S4: 접근 태그 확정은 앞단 게이트(RBAC 단계)에서 끝난다. 하위 계층은 태그를 소비만 한다. 이 테스트는 하위 계층 역할을
 * 하는 프로브 엔드포인트가 <b>재계산 없이</b> 태그를 읽을 수 있는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(AccessTagGateTest.프로브.class)
class AccessTagGateTest {

	private static final String USER_토큰 = "token-user";
	private static final String ADMIN_토큰 = "token-admin";
	private static final String 만료_토큰 = "token-expired";
	private static final String 역할없는_토큰 = "token-no-role";

	@Autowired private WebTestClient client;

	@Test
	@DisplayName("유효한 USER JWT면 인증되고 {public} 태그가 컨텍스트에 실린다")
	void USER_토큰이면_public_태그가_실린다() {
		태그를_요청한다(USER_토큰).expectStatus().isOk().expectBody(String.class).isEqualTo("public");
	}

	@Test
	@DisplayName("유효한 ADMIN JWT면 {public, restricted} 태그가 컨텍스트에 실린다")
	void ADMIN_토큰이면_restricted까지_실린다() {
		태그를_요청한다(ADMIN_토큰).expectStatus().isOk().expectBody(String.class).isEqualTo("public,restricted");
	}

	@Test
	@DisplayName("무효한 JWT는 401이다")
	void 무효한_토큰은_401이다() {
		태그를_요청한다("garbage").expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("만료된 JWT는 401이다")
	void 만료된_토큰은_401이다() {
		// 만료 판정 자체는 Spring Security의 JwtTimestampValidator가 한다. 여기서 확인하는 것은
		// 디코더가 검증 실패를 던졌을 때 우리 체인이 그것을 401로 바꾸는가다.
		태그를_요청한다(만료_토큰).expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("토큰이 없으면 401이다. 인증 안 된 요청은 어떤 하위 계층에도 닿지 않는다")
	void 토큰이_없으면_401이다() {
		client.get().uri("/probe/tags").exchange().expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("역할이 없는 유효 토큰은 태그가 비어 있다")
	void 역할이_없으면_태그가_비어_있다() {
		// 빈 태그로는 어떤 조각과도 교집합이 생기지 않으므로 검색 결과가 비어 있게 된다.
		// 인증은 됐지만 아무것도 못 보는 상태다. 조용히 public을 주지 않는다.
		태그를_요청한다(역할없는_토큰).expectStatus().isOk().expectBody().isEmpty();
	}

	private WebTestClient.ResponseSpec 태그를_요청한다(String token) {
		return client
				.get()
				.uri("/probe/tags")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange();
	}

	/** 하위 계층 대역. 태그를 재계산하지 않고 컨텍스트에서 읽기만 한다 (S4). */
	@TestConfiguration
	@RestController
	static class 프로브 {

		@GetMapping("/probe/tags")
		Mono<String> tags() {
			return AccessTags.current().map(tags -> tags.stream().sorted().reduce((a, b) -> a + "," + b).orElse(""));
		}

		/** Keycloak 없이 토큰을 해석한다. 실제 서명 검증은 Keycloak에 위임한다 (S25). */
		@Bean
		@Primary
		ReactiveJwtDecoder 대역_디코더() {
			return token ->
					switch (token) {
						case USER_토큰 -> Mono.just(jwt(token, "USER"));
						case ADMIN_토큰 -> Mono.just(jwt(token, "ADMIN"));
						case 역할없는_토큰 -> Mono.just(jwt(token));
						case 만료_토큰 -> Mono.error(new JwtValidationException("만료됨", List.of(new OAuth2Error("invalid_token"))));
						default -> Mono.error(new BadJwtException("서명이 유효하지 않다"));
					};
		}

		private static Jwt jwt(String token) {
			Instant now = Instant.now();
			return Jwt.withTokenValue(token)
					.header("alg", "RS256")
					.subject("subject-norole")
					.issuedAt(now)
					.expiresAt(now.plusSeconds(3600))
					.build();
		}

		private static Jwt jwt(String token, String role) {
			Instant now = Instant.now();
			return Jwt.withTokenValue(token)
					.header("alg", "RS256")
					.subject("subject-" + role)
					.issuedAt(now)
					.expiresAt(now.plusSeconds(3600))
					.claim("realm_access", Map.of("roles", List.of(role)))
					.build();
		}
	}
}
