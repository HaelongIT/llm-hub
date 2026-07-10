package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * REQ-AUTH 시나리오: 필터체인 실행 순서가 CORS → JWT → RBAC → RateLimit.
 *
 * <p>순서가 곧 보안이다. 태그 확정(RBAC)이 인증(JWT)보다 앞서면 인증되지 않은 주체의 태그를 계산하게 되고, RateLimit이
 * 앞서면 인증 실패한 요청까지 쿼터를 소모한다.
 *
 * <p>주석이나 문서가 아니라 <b>실제로 조립된 체인</b>에서 확인한다.
 */
@SpringBootTest
class SecurityFilterChainOrderTest {

	@Autowired private SecurityWebFilterChain chain;

	@Test
	@DisplayName("필터가 CORS → JWT 인증 → 태그 게이트(RBAC) → RateLimit 순으로 실행된다")
	void 필터체인_순서가_지켜진다() {
		List<String> 필터들 =
				chain.getWebFilters().map(f -> f.getClass().getSimpleName()).collectList().block();

		assertThat(필터들)
				.as("docs/01의 필터체인 순서. 어긋나면 앞단 게이트(S4)의 전제가 무너진다")
				.containsSubsequence(
						"CorsWebFilter", "AuthenticationWebFilter", "AccessTagGateWebFilter", "RateLimitWebFilter");
	}

	@Test
	@DisplayName("태그 게이트는 인가 필터보다 먼저 실행된다")
	void 태그_확정이_인가보다_먼저다() {
		List<String> 필터들 =
				chain.getWebFilters().map(f -> f.getClass().getSimpleName()).collectList().block();

		assertThat(필터들)
				.as("태그 확정은 RAG·검색 진입 이전에 완료된다 (REQ-AUTH 불변식)")
				.containsSubsequence("AccessTagGateWebFilter", "AuthorizationWebFilter");
	}
}
