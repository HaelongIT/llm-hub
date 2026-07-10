package com.llmhub.chat.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 그 세션이 없거나, 요청자의 것이 아니다 (S2).
 *
 * <p><b>403이 아니라 404다.</b> 남의 세션에 403을 주면 "그 ID의 세션이 존재한다"는 사실을 알려준다.
 * {@code SessionController}의 조회·삭제도 같은 방식이다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SessionNotFoundException extends RuntimeException {

	public SessionNotFoundException(UUID sessionId) {
		super("세션이 없거나 접근할 수 없다: " + sessionId);
	}
}
