package com.llmhub.idx.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 업로드가 허용목록·크기 검증에 걸려 거부되었다 (SEC-4).
 *
 * <p>클라이언트가 잘못 보낸 것이지 서버가 고장난 것이 아니다. 상태를 붙이지 않으면 500이 나가고, 운영자는 정상 거부를 장애로
 * 보고받는다.
 *
 * <p>거부 사유는 응답 본문에 싣지 않는다. 본문에는 추적 ID만 나가고(SEC-3), 사유는 그 ID로 로그에서 찾는다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UploadRejectedException extends RuntimeException {

	public UploadRejectedException(String message) {
		super(message);
	}
}
