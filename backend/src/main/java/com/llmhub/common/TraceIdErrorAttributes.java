package com.llmhub.common;

import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

/**
 * 실패 응답 본문에 추적 ID를 싣는다 (REL-3).
 *
 * <p>기본 본문의 {@code requestId}는 Netty의 요청 식별자라 우리 로그 어디에도 남지 않는다. 사용자가 신고할 값과
 * 운영자가 찾을 값이 같아야 한다.
 *
 * <p><b>예외 메시지는 싣지 않는다.</b> {@code server.error.include-message}를 켜지 않으므로 내부 예외
 * 문구가 밖으로 나가지 않는다 (SEC-3). 나가는 것은 추적 ID뿐이고, 원인은 그 ID로 로그에서 찾는다.
 */
@Component
public class TraceIdErrorAttributes extends DefaultErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
		Map<String, Object> attributes = super.getErrorAttributes(request, options);
		// TraceIdWebFilter가 여기에 심는다. 프레임워크의 로그 접두사도 같은 값을 쓴다.
		Object traceId = request.exchange().getAttribute(ServerWebExchange.LOG_ID_ATTRIBUTE);
		if (traceId != null) {
			attributes.put("traceId", traceId);
		}
		return attributes;
	}
}
