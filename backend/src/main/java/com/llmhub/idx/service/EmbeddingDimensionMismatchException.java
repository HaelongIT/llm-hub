package com.llmhub.idx.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 설정된 임베딩 차원이 기존 ES 인덱스의 차원과 다르다.
 *
 * <p>{@code dense_vector}의 차원은 인덱스 생성 시 고정된다(docs/03). 기존 인덱스에 다른 차원의 벡터를 넣을 수 없다.
 * 차원을 바꾸려면 <b>새 인덱스</b>가 필요하다 — 운영자가 {@code ES_INDEX_NAME}을 바꾸고 전체를 재색인해야 한다.
 *
 * <p>임베딩을 시작하기 <b>전에</b> 던진다. 실패가 확정된 작업에 임베딩 비용을 쓰지 않고, 구버전 조각도 건드리지 않는다
 * (S8-3 깨끗한 실패).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EmbeddingDimensionMismatchException extends RuntimeException {

	public EmbeddingDimensionMismatchException(int indexedDimensions, int configuredDimensions) {
		super(
				"임베딩 차원이 기존 인덱스와 다르다: 설정=%d, 인덱스=%d. 차원을 바꾸려면 새 인덱스가 필요하다 (docs/03)"
						.formatted(configuredDimensions, indexedDimensions));
	}
}
