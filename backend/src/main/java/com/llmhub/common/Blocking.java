package com.llmhub.common;

import java.util.concurrent.Callable;
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
 */
public final class Blocking {

	private Blocking() {}

	/** 값을 돌려주는 블로킹 작업. */
	public static <T> Mono<T> call(Callable<T> task) {
		return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
	}

	/** 값이 없는 블로킹 작업. */
	public static Mono<Void> run(Runnable task) {
		return Mono.<Void>fromRunnable(task).subscribeOn(Schedulers.boundedElastic());
	}
}
