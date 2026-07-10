package com.llmhub.common.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL-1: 무한 대기·행 방지.
 *
 * <p>게이트웨이가 연결은 받고 응답하지 않으면(Ollama가 모델을 로딩 중이거나 GPU에 묶였을 때 실제로 이렇게 된다), 타임아웃이
 * 없는 클라이언트는 <b>영원히</b> 매달린다. 이 호출은 {@code Blocking.call} 안에서 도므로 boundedElastic
 * 스레드가 그만큼 묶인다. 2코어 운영 VM이면 상한이 20이라 동시 요청 20건에 색인·검색·저장이 전부 정지한다.
 *
 * <p>실측(타임아웃 도입 전): 상대가 소켓을 닫을 때까지 반환하지 않았다.
 */
class LiteLlmEmbeddingTimeoutTest {

	private ServerSocket 먹통_서버;

	@BeforeEach
	void 응답하지_않는_서버를_띄운다() throws Exception {
		먹통_서버 = new ServerSocket(0);
		CompletableFuture.runAsync(() -> {
			try (Socket accepted = 먹통_서버.accept()) {
				Thread.sleep(30_000); // 절대 응답하지 않는다
			} catch (Exception ignored) {
				// 테스트 종료
			}
		});
	}

	@AfterEach
	void 서버를_내린다() throws Exception {
		먹통_서버.close();
	}

	@Test
	@DisplayName("게이트웨이가 응답하지 않으면 read 타임아웃 안에 포기한다")
	void 응답하지_않는_게이트웨이를_기다리지_않는다() {
		LiteLlmEmbeddingClient client =
				new LiteLlmEmbeddingClient(
						"http://127.0.0.1:" + 먹통_서버.getLocalPort(),
						"key",
						new EmbeddingSpec("m", 4),
						Duration.ofSeconds(2),
						Duration.ofMillis(500));

		Instant 시작 = Instant.now();
		assertThatThrownBy(() -> client.embed(List.of("안녕")))
				.as("행에 걸린 게이트웨이는 깨끗한 실패로 끝나야 한다 (REL-1, S8-3)")
				.isInstanceOf(EmbeddingException.class);

		assertThat(Duration.between(시작, Instant.now()))
				.as("타임아웃이 없으면 상대가 끊을 때까지 스레드가 묶인다")
				.isLessThan(Duration.ofSeconds(5));
	}
}
