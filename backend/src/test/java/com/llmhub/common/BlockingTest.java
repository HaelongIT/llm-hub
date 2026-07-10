package com.llmhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
