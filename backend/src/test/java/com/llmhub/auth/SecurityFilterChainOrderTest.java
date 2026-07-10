package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.llmhub.support.PostgresInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
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
@ContextConfiguration(initializers = PostgresInitializer.class)
class SecurityFilterChainOrderTest {

	/** 관리 포트용 체인이 따로 있다(REL-5). 검사 대상은 API를 지키는 체인이다. */
	@Autowired private List<SecurityWebFilterChain> chains;

	@Test
	@DisplayName("필터가 CORS → JWT 인증 → 태그 게이트(RBAC) → RateLimit 순으로 실행된다")
	void 필터체인_순서가_지켜진다() {
		assertThat(애플리케이션_체인의_필터들())
				.as("docs/01의 필터체인 순서. 어긋나면 앞단 게이트(S4)의 전제가 무너진다")
				.containsSubsequence(
						"CorsWebFilter", "AuthenticationWebFilter", "AccessTagGateWebFilter", "RateLimitWebFilter");
	}

	@Test
	@DisplayName("추적 ID 필터가 CORS·인증보다 먼저 실행된다")
	void 추적_ID가_가장_먼저_발급된다() {
		// 맨 앞은 Spring Security 자신의 인프라 필터(ServerWebExchangeReactorContextWebFilter)다.
		// 우리가 배치할 수 있는 가장 앞자리가 SecurityWebFiltersOrder.FIRST이며, 거기에 둔다.
		assertThat(애플리케이션_체인의_필터들())
				.as("인증에 실패해 거부되는 요청도 추적 ID를 가져야 원인을 찾을 수 있다 (REL-3)")
				.containsSubsequence("TraceIdWebFilter", "CorsWebFilter", "AuthenticationWebFilter");
	}

	@Test
	@DisplayName("태그 게이트는 인가 필터보다 먼저 실행된다")
	void 태그_확정이_인가보다_먼저다() {
		assertThat(애플리케이션_체인의_필터들())
				.as("태그 확정은 RAG·검색 진입 이전에 완료된다 (REQ-AUTH 불변식)")
				.containsSubsequence("AccessTagGateWebFilter", "AuthorizationWebFilter");
	}

	/** 인증을 수행하는 체인. 관리 포트 체인에는 인증 필터가 없다. */
	private List<String> 애플리케이션_체인의_필터들() {
		return chains.stream()
				.map(c -> c.getWebFilters().map(f -> f.getClass().getSimpleName()).collectList().block())
				.filter(필터들 -> 필터들.contains("AuthenticationWebFilter"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("인증 필터를 가진 체인이 없다"));
	}
}
