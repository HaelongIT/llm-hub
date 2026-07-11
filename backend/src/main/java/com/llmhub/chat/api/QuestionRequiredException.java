package com.llmhub.chat.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 질문이 비어 있다 (null 또는 공백뿐).
 *
 * <p>검증 없이 두면 새 세션 경로에서 {@code titleOf(null)}이 NPE를 내 SSE error/done 없이 raw 500이 되고,
 * 기존-세션 경로의 클린 error 처리와 불일치한다 (리뷰 F5, S8-3). 두 경로 모두 여기서 400으로 거부한다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class QuestionRequiredException extends RuntimeException {

	public QuestionRequiredException() {
		super("질문이 비어 있다");
	}
}
