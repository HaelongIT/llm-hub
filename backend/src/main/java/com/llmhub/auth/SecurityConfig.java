package com.llmhub.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 필터체인: <b>CORS → JWT → RBAC → RateLimit</b> (docs/01, S4, S10).
 *
 * <p>리액티브 스택이므로 서블릿 필터가 아니라 {@code WebFilter}를 쓴다. 위 순서는 {@link
 * SecurityWebFiltersOrder}의 위치에 대응한다: CORS는 인증보다 앞, JWT 인증은 AUTHENTICATION 대역,
 * 태그 게이트는 그 직후, RateLimit은 인가 뒤.
 *
 * <p>모든 API는 인증이 필요하다. 인증되지 않은 요청은 401이다 (SEC-1).
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

	@Bean
	RoleTagMapper roleTagMapper(AuthProperties properties) {
		return new ConfiguredRoleTagMapper(properties.roleTags());
	}

	@Bean
	SecurityWebFilterChain securityWebFilterChain(
			ServerHttpSecurity http, RoleTagMapper roleTagMapper, AuthProperties properties) {
		return http.cors(Customizer.withDefaults())
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.authorizeExchange(
						exchange ->
								exchange
										// 색인 API는 ADMIN만 (SEC-2). 컨트롤러가 아니라 여기서 판정한다 —
										// 권한 검사 누락 가능 경로가 생기지 않도록 구조로 강제한다.
										.pathMatchers(org.springframework.http.HttpMethod.POST, "/api/index")
										.hasRole("ADMIN")
										.anyExchange()
										.authenticated())
				.oauth2ResourceServer(
						oauth2 ->
								oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter())))
				// 앞단 게이트: 인증 직후, 인가와 하위 계층 진입 이전에 태그를 확정한다 (S4).
				.addFilterAfter(new AccessTagGateWebFilter(roleTagMapper), SecurityWebFiltersOrder.AUTHENTICATION)
				// v0는 자리만. 순서를 지금 확정해 두면 나중에 정책을 넣을 때 체인을 안 건드린다 (S10).
				.addFilterAfter(
						new RateLimitWebFilter(properties.rateLimitEnabled()), SecurityWebFiltersOrder.AUTHORIZATION)
				.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.corsAllowedOrigins());
		config.setAllowedMethods(java.util.List.of("GET", "POST", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(java.util.List.of("*"));
		config.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
