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
 * @param analyzer {@code chunk_text}의 BM25 분석기. v0=nori(한국어). 도메인 중립 ≠ 언어 중립이며 언어는 배포
 *     설정이다 (S15, E16). 바꾸면 <b>새 인덱스가 필요하다</b> — 매핑은 인덱스 생성 시 고정된다.
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
		String analyzer,
		String elasticsearchUrl,
		String elasticsearchUsername,
		String elasticsearchPassword) {}
