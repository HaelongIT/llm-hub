package com.llmhub.idx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 모델 고정 (S8-4). 코어는 모델명만 전달하고 라우팅은 게이트웨이가 한다 (S8-1, E7).
 *
 * <p>모델이나 차원을 바꾸면 재색인이 필요하다 (E9). 차원 변경은 새 ES 인덱스가 필요하다.
 */
@ConfigurationProperties(prefix = "llmhub.embedding")
public record EmbeddingProperties(
		String baseUrl,
		String apiKey,
		String model,
		int dimensions,
		/** 게이트웨이가 응답하지 않을 때 포기하는 시간. 없으면 무한 대기다 (REL-1). */
		java.time.Duration connectTimeout,
		java.time.Duration readTimeout) {}
