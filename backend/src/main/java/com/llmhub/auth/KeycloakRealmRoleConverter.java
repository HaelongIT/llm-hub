package com.llmhub.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Keycloak Realm Role → {@code GrantedAuthority} (docs/01).
 *
 * <p>역할은 {@code realm_access.roles} 클레임에 있다. 역할을 DB에 저장하지 않는다 — Keycloak이 부여하고
 * 요청 시점에 태그로 변환한다. 저장하면 이중 관리가 된다 (docs/03).
 */
public final class KeycloakRealmRoleConverter
		implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

	private static final String REALM_ACCESS = "realm_access";
	private static final String ROLES = "roles";

	@Override
	public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
		return Mono.just(new JwtAuthenticationToken(jwt, authorities(jwt)));
	}

	private static Collection<GrantedAuthority> authorities(Jwt jwt) {
		Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
		if (realmAccess == null || !(realmAccess.get(ROLES) instanceof List<?> roles)) {
			return Set.of();
		}
		return roles.stream()
				.map(String::valueOf)
				.map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
				.collect(Collectors.toUnmodifiableSet());
	}
}
