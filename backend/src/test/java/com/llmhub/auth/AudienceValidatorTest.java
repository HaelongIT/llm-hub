package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * R-16: 같은 realm 서명이라도 우리를 대상으로 하지 않은 토큰은 거부한다.
 *
 * <p>모든 Keycloak realm에는 기본 클라이언트(account·admin-cli 등)가 있어 같은 키로 서명된 토큰을 발급할 수 있다.
 * 서명·만료만 검증하면 그 토큰들이 통과한다. {@code aud}에 우리 대상이 들어 있는지 확인해 막는다.
 */
class AudienceValidatorTest {

	private static final String 대상 = "llmhub-backend";
	private final AudienceValidator validator = new AudienceValidator(대상);

	@Test
	@DisplayName("aud에 우리 대상이 있으면 통과한다")
	void 대상이_있으면_통과() {
		assertThat(validator.validate(토큰(List.of("llmhub-backend"))).hasErrors()).isFalse();
	}

	@Test
	@DisplayName("aud에 여러 대상이 있어도 우리 것이 포함되면 통과한다")
	void 여러_대상_중_우리것_포함이면_통과() {
		assertThat(validator.validate(토큰(List.of("account", "llmhub-backend"))).hasErrors()).isFalse();
	}

	@Test
	@DisplayName("aud에 우리 대상이 없으면 거부한다")
	void 대상이_없으면_거부() {
		assertThat(validator.validate(토큰(List.of("account"))).hasErrors())
				.as("다른 클라이언트용 토큰은 거부되어야 한다 (R-16)")
				.isTrue();
	}

	@Test
	@DisplayName("aud가 비어 있으면 거부한다")
	void aud가_없으면_거부() {
		assertThat(validator.validate(토큰(List.of())).hasErrors()).isTrue();
	}

	private static Jwt 토큰(List<String> audiences) {
		Jwt.Builder builder =
				Jwt.withTokenValue("token")
						.header("alg", "RS256")
						.subject("user")
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(300))
						.claim("scope", "openid");
		return audiences.isEmpty()
				? builder.claims(c -> {}).build()
				: builder.audience(audiences).build();
	}
}
