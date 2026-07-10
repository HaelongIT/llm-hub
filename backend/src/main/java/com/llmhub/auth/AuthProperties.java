package com.llmhub.auth;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 역할→태그 매핑과 RateLimit 스위치. 코드에 하드코딩하지 않는다 (REL-4).
 *
 * @param roleTags 역할 이름 → 접근 태그 집합. 교체해도 재색인이 필요 없다 (E4).
 * @param rateLimitEnabled v0는 자리만 (S10).
 * @param corsAllowedOrigins BFF만 외부 노출된다 (SEC-1).
 */
@ConfigurationProperties(prefix = "llmhub.auth")
public record AuthProperties(
		Map<String, Set<String>> roleTags, boolean rateLimitEnabled, java.util.List<String> corsAllowedOrigins) {}
