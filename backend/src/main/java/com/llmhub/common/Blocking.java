package com.llmhub.common;

import java.util.concurrent.Callable;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 블로킹 호출을 논블로킹 흐름에서 격리한다 (S13, PERF-1).
 *
 * <p>블로킹 DB·파일 I/O는 반드시 이 헬퍼를 거친다. 그래야 나중에 R2DBC로 바꾸거나 스레드풀을 조정하는 일이 한곳에서 끝난다
 * (E12).
 *
 * <p><b>{@code subscribeOn}이며 {@code publishOn}이 아니다.</b> {@code publishOn}은 하위
 * 연산자만 옮기므로 블로킹 <i>소스</i>가 이벤트 루프에 남는다. {@code subscribeOn}은 구독 자체를 옮겨 소스까지 격리
 * 스케줄러로 보낸다.
 *
 * <p>여기서 요청 컨텍스트의 추적 ID를 작업 스레드의 MDC로 옮긴다 (REL-3). 색인·검색·저장이 전부 이 경로를 지나므로,
 * 하위 계층이 추적 ID를 인자로 받아 끌고 다니지 않아도 로그가 요청과 이어진다. MDC를 리액티브 연산자 전반에 전파하지는
 * 않는다 — 스레드 로컬을 이벤트 루프에 심는 일이라 함정이 많다.
 */
public final class Blocking {

	private Blocking() {}

	/** 값을 돌려주는 블로킹 작업. */
	public static <T> Mono<T> call(Callable<T> task) {
		return Mono.deferContextual(ctx -> Mono.fromCallable(withTraceId(TraceId.from(ctx), task)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** 값이 없는 블로킹 작업. */
	public static Mono<Void> run(Runnable task) {
		return Mono.deferContextual(ctx -> Mono.<Void>fromRunnable(withTraceId(TraceId.from(ctx), task)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private static <T> Callable<T> withTraceId(String traceId, Callable<T> task) {
		return () -> {
			bind(traceId);
			try {
				return task.call();
			} finally {
				MDC.remove(TraceId.MDC_KEY);
			}
		};
	}

	private static Runnable withTraceId(String traceId, Runnable task) {
		return () -> {
			bind(traceId);
			try {
				task.run();
			} finally {
				MDC.remove(TraceId.MDC_KEY);
			}
		};
	}

	/**
	 * 스레드풀은 스레드를 재사용한다. 추적 ID가 없는 작업이 앞선 요청의 ID를 물려받으면 무관한 로그가 남의 요청에 묶인다. 심지
	 * 않을 때는 지운다.
	 */
	private static void bind(String traceId) {
		if (traceId == null) {
			MDC.remove(TraceId.MDC_KEY);
		} else {
			MDC.put(TraceId.MDC_KEY, traceId);
		}
	}
}
