package com.llmhub.common;

import java.util.UUID;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 요청마다 추적 ID를 발급해 응답 헤더와 요청 컨텍스트에 싣는다 (REL-3).
 *
 * <p>필터체인의 <b>맨 앞</b>에 선다. 인증에 실패해 401로 끊기는 요청에도 ID가 붙어야 거부의 원인을 추적할 수 있다.
 *
 * <p><b>클라이언트가 보낸 {@code X-Trace-Id}는 읽지 않는다.</b> 추적 ID는 감사 기록의 상관관계 키다(S5). 그
 * 값을 요청자가 고를 수 있으면 서로 다른 요청을 같은 ID로 묶거나 남의 ID를 사칭해 감사 흔적을 흐릴 수 있다.
 */
public final class TraceIdWebFilter implements WebFilter {

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String traceId = UUID.randomUUID().toString();
		// 응답이 커밋되기 전이다. 스트리밍 응답도 첫 바이트를 쓰기 전에 헤더가 확정된다.
		exchange.getResponse().getHeaders().set(TraceId.HEADER, traceId);

		// 500·404를 찍는 것은 우리 코드가 아니라 스프링의 에러 핸들러이고, 그 로그 줄은 MDC가 아니라
		// exchange의 로그 접두사를 쓴다. 여기를 바꾸지 않으면 실패한 요청만 추적이 끊긴다 — 하필
		// 추적이 가장 필요한 쪽이다. getLogPrefix()는 이 속성을 매번 다시 읽는다.
		exchange.getAttributes().put(ServerWebExchange.LOG_ID_ATTRIBUTE, traceId);

		return chain.filter(exchange).contextWrite(ctx -> TraceId.with(ctx, traceId));
	}
}
