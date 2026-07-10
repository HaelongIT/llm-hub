package com.llmhub.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검색 설정 (REL-4).
 *
 * @param topK 과도한 조각 반환으로 컨텍스트가 폭증하지 않게 한다 (PERF-3).
 * @param bm25Boost BM25 점수와 벡터 점수는 스케일이 다르다. 배포마다 튜닝한다.
 */
@ConfigurationProperties(prefix = "llmhub.search")
public record SearchProperties(String indexName, int topK, float bm25Boost, float vectorBoost) {}
