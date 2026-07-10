package com.llmhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * REL-3: 요청 단위 추적 ID를 발급하고 감사·로그에 연결한다.
 *
 * <p><b>클라이언트가 보낸 추적 ID는 쓰지 않는다.</b> 그대로 받으면 감사 로그의 {@code trace_id}와 서버 로그에
 * 임의 문자열을 주입할 수 있다. 추적 ID는 감사 기록의 상관관계 키이므로, 그 값을 요청자가 고를 수 있으면 서로 다른 요청을
 * 같은 ID로 묶어 감사 흔적을 흐릴 수 있다.
 */
class TraceIdWebFilterTest {

	private static final String 클라이언트가_보낸_값 = "attacker-supplied";

	private final TraceIdWebFilter filter = new TraceIdWebFilter();

	@Test
	@DisplayName("응답 헤더에 추적 ID가 붙는다")
	void 응답에_추적_ID가_붙는다() {
		MockServerWebExchange exchange = 요청();

		StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

		assertThat(exchange.getResponse().getHeaders().getFirst(TraceId.HEADER))
				.as("사용자가 장애를 신고할 때 이 값으로 로그와 감사 기록을 찾는다")
				.isNotBlank();
	}

	@Test
	@DisplayName("추적 ID가 요청 컨텍스트에 실린다")
	void 컨텍스트에_실린다() {
		MockServerWebExchange exchange = 요청();
		AtomicReference<String> 하위_계층이_읽은_값 = new AtomicReference<>();

		StepVerifier.create(
						filter.filter(exchange, e -> TraceId.current().doOnNext(하위_계층이_읽은_값::set).then()))
				.verifyComplete();

		assertThat(하위_계층이_읽은_값.get())
				.isEqualTo(exchange.getResponse().getHeaders().getFirst(TraceId.HEADER));
	}

	@Test
	@DisplayName("요청마다 다른 추적 ID가 발급된다")
	void 요청마다_다르다() {
		assertThat(발급된_추적_ID()).isNotEqualTo(발급된_추적_ID());
	}

	@Test
	@DisplayName("클라이언트가 보낸 X-Trace-Id는 무시된다")
	void 인바운드_헤더를_신뢰하지_않는다() {
		MockServerWebExchange exchange =
				MockServerWebExchange.from(
						MockServerHttpRequest.get("/api/sessions").header(TraceId.HEADER, 클라이언트가_보낸_값));
		AtomicReference<String> 컨텍스트_값 = new AtomicReference<>();

		StepVerifier.create(filter.filter(exchange, e -> TraceId.current().doOnNext(컨텍스트_값::set).then()))
				.verifyComplete();

		assertThat(컨텍스트_값.get())
				.as("추적 ID를 요청자가 고를 수 있으면 감사 기록의 상관관계를 조작할 수 있다")
				.isNotEqualTo(클라이언트가_보낸_값);
		assertThat(exchange.getResponse().getHeaders().getFirst(TraceId.HEADER))
				.isNotEqualTo(클라이언트가_보낸_값);
	}

	private String 발급된_추적_ID() {
		MockServerWebExchange exchange = 요청();
		StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();
		return exchange.getResponse().getHeaders().getFirst(TraceId.HEADER);
	}

	private static MockServerWebExchange 요청() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/api/sessions"));
	}
}
