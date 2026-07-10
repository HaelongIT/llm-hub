package com.llmhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * S13: 블로킹 접근은 격리 스케줄러에서만 실행된다. PERF-1: 논블로킹 흐름을 블로킹 호출이 막지 않는다.
 *
 * <p>BlockHound만으로는 이 불변식을 지킬 수 없다. JDK 21에서 {@code Thread.sleep} 계열을 더 이상 잡지
 * 못하는 구멍이 있어, "통과했다"가 "블로킹이 없다"를 뜻하지 않는다. 그래서 <b>실제 실행 스레드 이름</b>을 직접 단언한다.
 */
class BlockingTest {

	@Test
	@DisplayName("블로킹 작업은 boundedElastic 스레드에서 실행된다")
	void 블로킹_작업은_격리_스케줄러에서_돈다() {
		AtomicReference<String> 실행_스레드 = new AtomicReference<>();

		Mono<String> mono = Blocking.call(() -> {
			실행_스레드.set(Thread.currentThread().getName());
			return "결과";
		});

		StepVerifier.create(mono).expectNext("결과").verifyComplete();

		assertThat(실행_스레드.get())
				.as("논블로킹 스레드에서 블로킹 I/O를 하면 이벤트 루프가 굶는다")
				.startsWith("boundedElastic-");
	}

	@Test
	@DisplayName("블로킹 작업은 구독한 스레드를 점유하지 않는다")
	void 구독_스레드를_점유하지_않는다() {
		AtomicReference<String> 구독_스레드 = new AtomicReference<>();
		AtomicReference<String> 실행_스레드 = new AtomicReference<>();

		Mono<String> mono =
				Mono.fromRunnable(() -> 구독_스레드.set(Thread.currentThread().getName()))
						.then(Blocking.call(() -> {
							실행_스레드.set(Thread.currentThread().getName());
							return "결과";
						}))
						.subscribeOn(Schedulers.parallel());

		StepVerifier.create(mono).expectNext("결과").verifyComplete();

		assertThat(구독_스레드.get()).startsWith("parallel-");
		assertThat(실행_스레드.get())
				.as("subscribeOn(boundedElastic)이 소스까지 옮겨야 한다. publishOn이면 소스가 남는다")
				.startsWith("boundedElastic-");
	}

	@Test
	@DisplayName("블로킹 작업의 예외는 그대로 전파된다")
	void 예외가_전파된다() {
		Mono<String> mono = Blocking.call(() -> {
			throw new IllegalStateException("저장 실패");
		});

		StepVerifier.create(mono)
				.expectErrorMatches(e -> e instanceof IllegalStateException && "저장 실패".equals(e.getMessage()))
				.verify();
	}

	@Test
	@DisplayName("반환값 없는 블로킹 작업도 격리된다")
	void 반환값_없는_작업도_격리된다() {
		AtomicReference<String> 실행_스레드 = new AtomicReference<>();

		StepVerifier.create(Blocking.run(() -> 실행_스레드.set(Thread.currentThread().getName()))).verifyComplete();

		assertThat(실행_스레드.get()).startsWith("boundedElastic-");
	}

	// REL-3: 색인·검색·저장은 전부 이 헬퍼를 지난다. 여기서 추적 ID를 MDC에 심으면
	// 격리 스케줄러에서 도는 모든 로그가 요청과 이어진다. 하위 계층이 traceId를 인자로
	// 받아 끌고 다닐 필요가 없다.

	@Test
	@DisplayName("블로킹 작업의 MDC에 요청 컨텍스트의 추적 ID가 실린다")
	void 추적_ID가_MDC에_실린다() {
		Mono<String> mono =
				Blocking.call(() -> MDC.get(TraceId.MDC_KEY)).contextWrite(ctx -> TraceId.with(ctx, "trace-1"));

		StepVerifier.create(mono).expectNext("trace-1").verifyComplete();
	}

	@Test
	@DisplayName("반환값 없는 블로킹 작업에도 추적 ID가 실린다")
	void 반환값_없는_작업에도_추적_ID가_실린다() {
		AtomicReference<String> 작업이_본_값 = new AtomicReference<>();

		StepVerifier.create(
						Blocking.run(() -> 작업이_본_값.set(MDC.get(TraceId.MDC_KEY)))
								.contextWrite(ctx -> TraceId.with(ctx, "trace-2")))
				.verifyComplete();

		assertThat(작업이_본_값.get()).isEqualTo("trace-2");
	}

	@Test
	@DisplayName("추적 ID가 없는 흐름에서는 MDC가 비어 있다")
	void 추적_ID가_없으면_MDC도_비어_있다() {
		// 스레드풀은 스레드를 재사용한다. 이전 요청의 추적 ID가 남아 있으면 무관한 로그가
		// 남의 요청에 묶인다 — 감사 추적을 신뢰할 수 없게 만든다.
		StepVerifier.create(Blocking.call(() -> String.valueOf(MDC.get(TraceId.MDC_KEY))))
				.expectNext("null")
				.verifyComplete();
	}

	@Test
	@DisplayName("작업이 끝나면 스레드에 추적 ID가 남지 않는다")
	void 작업이_끝나면_MDC가_정리된다() throws InterruptedException {
		StepVerifier.create(Blocking.call(() -> "결과").contextWrite(ctx -> TraceId.with(ctx, "trace-3")))
				.expectNext("결과")
				.verifyComplete();

		// 방금 반납된 워커를 다시 잡아 MDC를 들여다본다. 풀이 그 워커를 고르지 않으면
		// 이 단언은 통과만 하고 아무것도 잡지 못한다 — 누출을 놓칠 수는 있어도 없는 누출을
		// 만들어내지는 않는다.
		AtomicReference<String> 반납된_워커의_값 = new AtomicReference<>("아직 안 읽음");
		CountDownLatch 읽었다 = new CountDownLatch(1);
		Schedulers.boundedElastic()
				.schedule(
						() -> {
							반납된_워커의_값.set(MDC.get(TraceId.MDC_KEY));
							읽었다.countDown();
						});

		assertThat(읽었다.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(반납된_워커의_값.get()).isNull();
	}
}
