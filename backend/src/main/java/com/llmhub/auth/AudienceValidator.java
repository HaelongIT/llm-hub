package com.llmhub.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 토큰의 {@code aud}에 우리 대상이 들어 있는지 검증한다 (R-16).
 *
 * <p>서명·만료만 보면 같은 realm의 <b>다른 클라이언트</b>(기본 클라이언트 account·admin-cli 포함)가 발급한
 * 토큰도 통과한다. 그 토큰들은 우리를 대상으로 하지 않으므로 {@code aud}로 걸러낸다. issuer 검증은 다른 realm을,
 * 이 검증기는 같은 realm의 다른 대상을 막는다.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

	private final String requiredAudience;

	public AudienceValidator(String requiredAudience) {
		this.requiredAudience = requiredAudience;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt token) {
		if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
			return OAuth2TokenValidatorResult.success();
		}
		return OAuth2TokenValidatorResult.failure(
				new OAuth2Error(
						"invalid_token",
						"필요한 audience가 없다: " + requiredAudience,
						"https://tools.ietf.org/html/rfc6750#section-3.1"));
	}
}
