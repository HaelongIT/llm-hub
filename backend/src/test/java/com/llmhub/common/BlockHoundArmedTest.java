package com.llmhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * BlockHound가 실제로 무장되어 있는지 확인한다.
 *
 * <p>안전장치는 위반을 주입해 검증해야 한다. "테스트가 통과했다"가 "블로킹이 없다"를 뜻하려면, 먼저 "블로킹이 있으면 실패한다"가
 * 참이어야 한다.
 */
class BlockHoundArmedTest {

	@BeforeAll
	static void 무장한다() {
		BlockHound.install();
	}

	@Test
	@DisplayName("논블로킹 스레드에서 파일을 읽으면 BlockHound가 잡는다")
	void 논블로킹_스레드의_블로킹_IO를_잡는다() throws Exception {
		File 임시 = File.createTempFile("blockhound", ".txt");
		임시.deleteOnExit();
		Files.writeString(임시.toPath(), "x");

		Throwable 잡힌_것 = null;
		try {
			Mono.fromCallable(() -> {
						try (FileInputStream in = new FileInputStream(임시)) {
							return in.read();
						}
					})
					.subscribeOn(Schedulers.parallel())
					.block();
		} catch (Throwable t) {
			잡힌_것 = t;
		}

		assertThat(잡힌_것)
				.as("BlockHound가 무장되지 않았다면 이 블로킹 호출이 조용히 통과한다")
				.isNotNull();
		assertThat(원인_사슬(잡힌_것)).anyMatch(t -> t instanceof BlockingOperationError);
	}

	@Test
	@DisplayName("격리 스케줄러(boundedElastic)에서의 블로킹은 허용된다")
	void 격리_스케줄러의_블로킹은_허용된다() throws Exception {
		File 임시 = File.createTempFile("blockhound", ".txt");
		임시.deleteOnExit();
		Files.writeString(임시.toPath(), "x");

		Integer 읽은_바이트 =
				Blocking.call(() -> {
							try (FileInputStream in = new FileInputStream(임시)) {
								return in.read();
							}
						})
						.block();

		assertThat(읽은_바이트).isEqualTo('x');
	}

	private static java.util.List<Throwable> 원인_사슬(Throwable t) {
		java.util.List<Throwable> chain = new java.util.ArrayList<>();
		for (Throwable current = t; current != null && !chain.contains(current); current = current.getCause()) {
			chain.add(current);
		}
		return chain;
	}
}
