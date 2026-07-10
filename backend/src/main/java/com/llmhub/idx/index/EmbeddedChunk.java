package com.llmhub.idx.index;

/**
 * 벡터가 붙은 조각. ES에 저장되기 직전의 형태다.
 *
 * <p>벡터는 {@link IndexedChunk#embeddingModel()} 모델로 만들어졌고 차원은 {@link
 * IndexedChunk#embeddingDim()}과 같아야 한다. 색인과 검색의 임베딩 모델이 다르면 에러 없이 검색 품질만 붕괴한다
 * (S8-4).
 */
public record EmbeddedChunk(IndexedChunk chunk, float[] embedding) {

	public EmbeddedChunk {
		if (embedding.length != chunk.embeddingDim()) {
			throw new IllegalArgumentException(
					"벡터 차원이 메타데이터와 다르다: %d != %d".formatted(embedding.length, chunk.embeddingDim()));
		}
		embedding = embedding.clone();
	}

	@Override
	public float[] embedding() {
		return embedding.clone();
	}
}
