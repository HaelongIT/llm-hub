package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.web.server.ServerWebExchange;

/**
 * 관리 포트 헬스 개방의 <b>범위 제한</b>이 실제로 지켜지는지 (SEC-1, REL-5).
 *
 * <p>이 매처가 언제 매치하느냐가 곧 "헬스가 인증 없이 열리는 조건"이다. 특히 관리 포트를 앱 포트와 같게 <b>오설정</b>하면
 * 매치하지 않아야 한다 — 매치하면 애플리케이션 포트의 헬스가 인증 없이 열려 SEC-1이 붕괴한다. 그 방어 분기를 이 테스트가
 * 강제한다(없으면 분기를 지워도 무검출이었다).
 */
class ManagementPortMatcherTest {

	private static MatchResult 판정(Integer 관리포트, Integer 앱포트, int 요청포트) {
		MockEnvironment env = new MockEnvironment();
		if (관리포트 != null) {
			env.setProperty("local.management.port", 관리포트.toString());
		}
		if (앱포트 != null) {
			env.setProperty("local.server.port", 앱포트.toString());
		}
		return new ManagementPortMatcher(env).matches(요청(요청포트)).block();
	}

	/** MockServerHttpRequest 빌더에는 localAddress 설정이 없어 데코레이터로 덮는다. */
	private static ServerWebExchange 요청(int 로컬포트) {
		MockServerHttpRequest base = MockServerHttpRequest.get("/actuator/health").build();
		ServerHttpRequestDecorator decorated =
				new ServerHttpRequestDecorator(base) {
					@Override
					public InetSocketAddress getLocalAddress() {
						return new InetSocketAddress("127.0.0.1", 로컬포트);
					}
				};
		return MockServerWebExchange.from(base).mutate().request(decorated).build();
	}

	@Test
	@DisplayName("관리 포트로 온 요청은 매치한다 — 헬스를 연다")
	void 관리포트_요청은_매치한다() {
		assertThat(판정(9090, 8080, 9090).isMatch()).isTrue();
	}

	@Test
	@DisplayName("앱 포트로 온 요청은 매치하지 않는다 — 앱 포트 헬스는 인증 뒤에 남는다")
	void 앱포트_요청은_매치하지_않는다() {
		assertThat(판정(9090, 8080, 8080).isMatch()).isFalse();
	}

	@Test
	@DisplayName("관리 포트가 앱 포트와 같게 오설정되면 매치하지 않는다 (SEC-1 방어)")
	void 관리와_앱이_같으면_매치하지_않는다() {
		assertThat(판정(8080, 8080, 8080).isMatch())
				.as("같으면 매치해선 안 된다 — 매치하면 앱 포트 헬스가 인증 없이 열린다")
				.isFalse();
	}

	@Test
	@DisplayName("관리 포트가 설정되지 않으면 매치하지 않는다")
	void 관리포트_미설정이면_매치하지_않는다() {
		assertThat(판정(null, 8080, 8080).isMatch()).isFalse();
	}
}
