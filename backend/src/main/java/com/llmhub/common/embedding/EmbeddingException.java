package com.llmhub.common.embedding;

/**
 * 임베딩 게이트웨이 호출이 실패했다.
 *
 * <p>v0는 깨끗한 실패다. 자동 우회·페일오버 없이 명확한 에러로 끝낸다 (S8-3).
 */
public class EmbeddingException extends RuntimeException {

	public EmbeddingException(String message) {
		super(message);
	}

	public EmbeddingException(String message, Throwable cause) {
		super(message, cause);
	}
}
