package com.llmhub.chat.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 질문이 설정된 상한보다 길다 (PERF-5, SEC-4).
 *
 * <p>이력은 조립기가 예산으로 자르지만 <b>지금 질문은 자를 수 없다</b> — 잘라내면 사용자가 묻지 않은 것을 묻게 된다. 그래서
 * 거부한다. 이것이 실제로 컨텍스트를 넘기는 경로다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class QuestionTooLongException extends RuntimeException {

	public QuestionTooLongException(int actualChars, int maxChars) {
		super("질문이 너무 길다: %d자 (상한 %d자)".formatted(actualChars, maxChars));
	}
}
