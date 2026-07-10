package com.llmhub.auth;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 설정으로 주어진 역할→태그 표를 그대로 적용한다 (REL-4).
 *
 * <p>v0: {@code USER → {public}}, {@code ADMIN → {public, restricted}}.
 *
 * <p>모르는 역할은 아무 태그도 주지 않는다. 조용히 기본 태그를 주면 권한 모델이 무너진다.
 */
public final class ConfiguredRoleTagMapper implements RoleTagMapper {

	private final Map<String, Set<String>> tagsByRole;

	public ConfiguredRoleTagMapper(Map<String, Set<String>> tagsByRole) {
		this.tagsByRole =
				tagsByRole.entrySet().stream()
						.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
	}

	@Override
	public Set<String> tagsFor(Set<String> roles) {
		return roles.stream()
				.map(role -> tagsByRole.getOrDefault(role, Set.of()))
				.flatMap(Set::stream)
				.collect(Collectors.toUnmodifiableSet());
	}
}
