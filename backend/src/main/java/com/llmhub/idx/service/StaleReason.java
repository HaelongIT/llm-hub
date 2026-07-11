package com.llmhub.idx.service;

/**
 * 문서가 왜 재색인 대상인지 (E9, E11).
 *
 * <p>재색인 대상은 임베딩 모델이 바뀌었거나, 청킹 전략이 바뀌었거나, 둘 다다. 운영자가 무엇 때문에 다시 색인해야 하는지
 * 알아야 한다.
 */
public enum StaleReason {
	/** 색인 임베딩 모델이 현재 설정과 다르다. */
	MODEL,
	/** 청킹 버전이 현재 설정과 다르다. */
	CHUNKING,
	/** 둘 다 다르다. */
	MODEL_AND_CHUNKING
}
