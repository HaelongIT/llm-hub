package com.llmhub.auth;

import com.llmhub.common.TraceIdWebFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 필터체인: <b>TraceId → CORS → JWT → RBAC → RateLimit</b> (docs/01, S4, S10, REL-3).
 *
 * <p>리액티브 스택이므로 서블릿 필터가 아니라 {@code WebFilter}를 쓴다. 위 순서는 {@link
 * SecurityWebFiltersOrder}의 위치에 대응한다: 추적 ID는 맨 앞, CORS는 인증보다 앞, JWT 인증은
 * AUTHENTICATION 대역, 태그 게이트는 그 직후, RateLimit은 인가 뒤.
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

	/**
	 * 관리 포트의 헬스 엔드포인트만 인증 없이 연다 (REL-5).
	 *
	 * <p>컨테이너 오케스트레이터는 자격증명 없이 기동 완료를 확인해야 한다. 관리 포트는 호스트에 publish하지 않으므로
	 * 컨테이너 밖에서 보이지 않는다.
	 *
	 * <p>범위를 포트로 좁힌다. 경로로만 열면 애플리케이션 포트의 헬스까지 열려 SEC-1이 깨진다. 관리 포트가 설정되지
	 * 않은 배포에서는 이 체인이 아무 요청과도 매치하지 않고, 액추에이터는 인증 뒤에 남는다.
	 */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityWebFilterChain managementSecurityWebFilterChain(ServerHttpSecurity http, Environment environment) {
		return http.securityMatcher(
						new AndServerWebExchangeMatcher(
								new ManagementPortMatcher(environment),
								ServerWebExchangeMatchers.pathMatchers("/actuator/health", "/actuator/health/**")))
				.authorizeExchange(exchange -> exchange.anyExchange().permitAll())
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.build();
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
				// 맨 앞. 인증에 실패해 401로 끊기는 요청에도 추적 ID가 붙어야 한다 (REL-3).
				.addFilterAt(new TraceIdWebFilter(), SecurityWebFiltersOrder.FIRST)
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
