package com.llmhub.auth;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * <b>앞단 게이트</b>. 인증된 주체의 역할을 접근 태그로 확정해 요청 컨텍스트에 싣는다 (S4).
 *
 * <p>필터체인의 RBAC 단계다. 여기서 권한 확정이 끝나므로, RAG를 우회하는 경로가 나중에 생겨도 권한 누락이 불가능하다.
 * 하위 계층(SEARCH·CHAT)은 태그를 소비만 하며 재판단하지 않는다.
 *
 * <p>서블릿 전용 {@code OncePerRequestFilter}는 WebFlux에서 쓸 수 없다. 리액티브 대응물은 {@code
 * WebFilter}다.
 */
public final class AccessTagGateWebFilter implements WebFilter {

	private static final String ROLE_PREFIX = "ROLE_";

	private final RoleTagMapper roleTagMapper;

	public AccessTagGateWebFilter(RoleTagMapper roleTagMapper) {
		this.roleTagMapper = roleTagMapper;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.map(this::tagsOf)
				// 인증 전이면 태그가 없다. 인가 필터가 뒤에서 요청을 막는다.
				.defaultIfEmpty(Set.of())
				.flatMap(tags -> chain.filter(exchange).contextWrite(ctx -> AccessTags.with(ctx, tags)));
	}

	private Set<String> tagsOf(Authentication authentication) {
		Set<String> roles =
				authentication.getAuthorities().stream()
						.map(GrantedAuthority::getAuthority)
						.map(a -> a.startsWith(ROLE_PREFIX) ? a.substring(ROLE_PREFIX.length()) : a)
						.collect(Collectors.toUnmodifiableSet());
		return roleTagMapper.tagsFor(roles);
	}
}
