package com.llmhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

/**
 * 실패 응답 본문이 <b>추적 ID만</b> 싣고 예외 문구는 싣지 않는지 (REL-3, SEC-3).
 *
 * <p>이 방어가 무검증이면, 에러 본문에 예외 문구를 노출하도록 바꿔도 아무 테스트가 잡지 못한다. ES 인덱스명·게이트웨이
 * 주소 같은 내부 사정이 그 문구로 새 나간다.
 */
class TraceIdErrorAttributesTest {

	private static final String 내부_문구 = "ES 인덱스 llmhub-chunks 연결 실패 at 10.0.0.5:9200";

	private final TraceIdErrorAttributes attributes = new TraceIdErrorAttributes();

	@Test
	@DisplayName("추적 ID를 본문에 싣는다")
	void 추적ID를_싣는다() {
		Map<String, Object> result = 본문(exchangeWith("trace-xyz", new IllegalStateException(내부_문구)));

		assertThat(result).containsEntry("traceId", "trace-xyz");
	}

	@Test
	@DisplayName("예외 문구는 본문 어디에도 나타나지 않는다 (SEC-3)")
	void 예외_문구를_싣지_않는다() {
		Map<String, Object> result = 본문(exchangeWith("trace-xyz", new IllegalStateException(내부_문구)));

		assertThat(result).doesNotContainKey("message");
		assertThat(result.toString())
				.as("내부 예외 문구가 본문 어디에도 새 나가면 안 된다")
				.doesNotContain(내부_문구)
				.doesNotContain("llmhub-chunks")
				.doesNotContain("10.0.0.5");
	}

	private Map<String, Object> 본문(ServerWebExchange exchange) {
		ServerRequest request = ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
		return attributes.getErrorAttributes(request, ErrorAttributeOptions.defaults());
	}

	private ServerWebExchange exchangeWith(String traceId, Throwable error) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
		exchange.getAttributes().put(ServerWebExchange.LOG_ID_ATTRIBUTE, traceId);
		attributes.storeErrorInformation(error, exchange);
		return exchange;
	}
}
