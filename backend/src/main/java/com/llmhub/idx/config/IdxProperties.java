package com.llmhub.idx.config;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 색인 설정. 전부 설정값이며 코드에 하드코딩하지 않는다 (REL-4).
 *
 * @param allowedUploads 확장자 → 허용 MIME 집합. 허용목록 방식이다 (SEC-4).
 * @param maxUploadBytes 업로드 크기 상한 (SEC-4).
 * @param storageRoot 원본 보관 루트. 경로는 시스템이 생성한다 (S16).
 * @param chunkSizeTokens 토큰 크기 기준 청킹 (S12).
 * @param indexName ES 조각 인덱스.
 * @param elasticsearchUrl 내부망 전용 (SEC-1).
 * @param elasticsearchUsername 운영에서는 ES 보안이 켜져 있다. 비어 있으면 인증하지 않는다.
 * @param elasticsearchPassword 비밀정보다. 코드·저장소에 넣지 않는다 (SEC-3).
 */
@ConfigurationProperties(prefix = "llmhub.idx")
public record IdxProperties(
		Map<String, Set<String>> allowedUploads,
		long maxUploadBytes,
		String storageRoot,
		int chunkSizeTokens,
		String indexName,
		String elasticsearchUrl,
		String elasticsearchUsername,
		String elasticsearchPassword) {}
