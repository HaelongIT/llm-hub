package com.llmhub.common;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 요청 단위 추적 ID를 하위 계층에 전달한다 (REL-3).
 *
 * <p>{@link com.llmhub.auth.AccessTags}와 같은 방식이다. Reactor {@code Context}에 실어 나르므로
 * 스레드와 무관하게 스트리밍 응답의 모든 연산자에서 보인다.
 *
 * <p>블로킹 계층(색인·검색·저장)은 {@link Blocking}이 이 값을 MDC에 옮겨 주므로 인자로 받을 필요가 없다. 리액티브
 * 계층은 {@link #current()}로 읽는다.
 */
public final class TraceId {

	/** 응답 헤더. 사용자가 장애를 신고할 때 이 값으로 로그와 감사 기록을 찾는다. */
	public static final String HEADER = "X-Trace-Id";

	/** 로그 패턴({@code logging.pattern.correlation})이 읽는 MDC 키. */
	public static final String MDC_KEY = "traceId";

	private static final String KEY = TraceId.class.getName();

	private TraceId() {}

	/** 필터가 발급한 ID를 싣는다. */
	static Context with(Context context, String traceId) {
		return context.put(KEY, traceId);
	}

	/** 없으면 {@code null}. */
	static String from(ContextView context) {
		return context.getOrDefault(KEY, null);
	}

	/** 리액티브 계층이 읽는다. 필터를 통과하지 않았으면 빈 문자열이다. */
	public static Mono<String> current() {
		return Mono.deferContextual(ctx -> Mono.just(ctx.getOrDefault(KEY, "")));
	}
}
