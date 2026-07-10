package com.llmhub.auth;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 필터체인의 RateLimit 자리 (S10).
 *
 * <p>v0는 실질 정책이 없다. 자리만 지킨다. 순서(…→RateLimit)를 지금 확정해 두면, 나중에 정책을 넣을 때 체인 구조를
 * 건드리지 않아도 된다.
 */
public final class RateLimitWebFilter implements WebFilter {

	private final boolean enabled;

	public RateLimitWebFilter(boolean enabled) {
		this.enabled = enabled;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!enabled) {
			return chain.filter(exchange);
		}
		// v0에는 정책이 없다. 활성화해도 통과시킨다.
		return chain.filter(exchange);
	}
}
