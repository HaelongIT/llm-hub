package com.llmhub.common.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL-1: ES 호출도 무한 대기하지 않는다.
 *
 * <p>ES가 연결은 받고 응답하지 않으면(장애·과부하·매핑 폭주), 응답 타임아웃이 없는 클라이언트는 매달린다. 이 호출은
 * {@code Blocking.call} 안에서 도므로 boundedElastic 스레드가 묶인다.
 *
 * <p>실측(타임아웃 도입 전): 먹통 소켓에 대해 200초를 넘겨도 반환하지 않았다. Rest5 기본 소켓 타임아웃(30s)은 fluent
 * {@code ElasticsearchTransportConfig} 경로에 적용되지 않았고, 기본 응답 타임아웃은 0(무한)이다.
 */
class ElasticsearchTimeoutTest {

	private ServerSocket 먹통_서버;

	@BeforeEach
	void 응답하지_않는_서버를_띄운다() throws Exception {
		먹통_서버 = new ServerSocket(0);
		CompletableFuture.runAsync(() -> {
			try (Socket accepted = 먹통_서버.accept()) {
				Thread.sleep(200_000); // 연결만 받고 절대 응답하지 않는다
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
	@DisplayName("ES가 응답하지 않으면 응답 타임아웃 안에 포기한다")
	void 응답하지_않는_ES를_기다리지_않는다() {
		ElasticsearchClient client =
				ElasticsearchClientFactory.create(
						"http://127.0.0.1:" + 먹통_서버.getLocalPort(),
						"",
						"",
						Duration.ofSeconds(2),
						Duration.ofMillis(500));

		// 타임아웃이 없으면 여기서 매달린다. preemptive 상한으로 행을 막고 실패로 끝낸다.
		assertTimeoutPreemptively(
				Duration.ofSeconds(8),
				() ->
						assertThatThrownBy(() -> client.indices().exists(e -> e.index("probe")))
								.as("행에 걸린 ES는 깨끗한 실패로 끝나야 한다 (REL-1, S8-3)")
								.isInstanceOf(Exception.class));
	}

	@Test
	@DisplayName("기본 타임아웃 오버로드도 클라이언트를 만든다")
	void 기본_타임아웃_오버로드() {
		assertThat(ElasticsearchClientFactory.create("http://127.0.0.1:" + 먹통_서버.getLocalPort(), "", ""))
				.isNotNull();
	}
}
