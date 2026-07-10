package com.llmhub.idx.index;

/**
 * 색인에 쓴 임베딩 모델과 차원 (설정값으로 고정, S8-4).
 *
 * <p>조각마다 기록한다. 임베딩을 바꾸면 재색인이 필요하고, 재색인 대상은 이 값으로 식별한다 (E9). 색인과 검색의 임베딩 모델이
 * 다르면 에러 없이 검색 품질만 붕괴한다.
 */
public record EmbeddingSpec(String model, int dimensions) {

	public EmbeddingSpec {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("임베딩 모델명이 비어 있다");
		}
		if (dimensions <= 0) {
			throw new IllegalArgumentException("임베딩 차원은 양수여야 한다: " + dimensions);
		}
	}
}
