package com.llmhub.idx.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmhub.idx.index.EmbeddingSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LiteLLM 게이트웨이(OpenAI 호환 {@code /v1/embeddings})를 호출한다.
 *
 * <p>S8-1: 코어는 모델명만 전달한다. 라우팅·페일오버는 게이트웨이 책임이다. E7: 특정 모델을 코어에 하드코딩하지 않는다.
 *
 * <p>게이트웨이는 JDK 내장 HTTP 서버로 흉내 낸다. 목 서버 라이브러리를 의존성에 추가하지 않는다.
 */
class LiteLlmEmbeddingClientTest {

	private static final EmbeddingSpec 스펙 = new EmbeddingSpec("bge-m3", 3);

	private HttpServer 게이트웨이;
	private final AtomicReference<String> 받은_본문 = new AtomicReference<>();
	private final AtomicReference<String> 받은_인증헤더 = new AtomicReference<>();

	@BeforeEach
	void 게이트웨이를_띄운다() throws IOException {
		게이트웨이 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		게이트웨이.start();
	}

	@AfterEach
	void 게이트웨이를_내린다() {
		게이트웨이.stop(0);
	}

	private LiteLlmEmbeddingClient 클라이언트() {
		return new LiteLlmEmbeddingClient("http://127.0.0.1:" + 게이트웨이.getAddress().getPort(), "test-key", 스펙);
	}

	private void 응답한다(int status, String body) {
		게이트웨이.createContext("/v1/embeddings", exchange -> {
			받은_본문.set(읽는다(exchange));
			받은_인증헤더.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
	}

	@Test
	@DisplayName("텍스트 순서대로 벡터를 돌려준다")
	void 벡터를_순서대로_반환한다() {
		응답한다(
				200,
				"""
				{"data":[
				  {"index":0,"embedding":[0.1,0.2,0.3]},
				  {"index":1,"embedding":[0.4,0.5,0.6]}
				]}""");

		List<float[]> vectors = 클라이언트().embed(List.of("첫째", "둘째"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
		assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
	}

	@Test
	@DisplayName("설정된 모델명과 API 키를 게이트웨이에 전달한다")
	void 모델명과_키를_전달한다() {
		응답한다(200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");

		클라이언트().embed(List.of("질문"));

		assertThat(받은_본문.get()).contains("\"model\":\"bge-m3\"").contains("질문");
		assertThat(받은_인증헤더.get()).isEqualTo("Bearer test-key");
	}

	@Test
	@DisplayName("응답 벡터의 차원이 설정과 다르면 거부한다")
	void 차원_불일치를_거부한다() {
		응답한다(200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");

		assertThatThrownBy(() -> 클라이언트().embed(List.of("질문")))
				.as("차원이 어긋나면 검색이 에러 없이 조용히 붕괴한다 (S8-4)")
				.isInstanceOf(EmbeddingException.class)
				.hasMessageContaining("차원");
	}

	@Test
	@DisplayName("응답 개수가 입력 개수와 다르면 거부한다")
	void 개수_불일치를_거부한다() {
		응답한다(200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");

		assertThatThrownBy(() -> 클라이언트().embed(List.of("첫째", "둘째")))
				.isInstanceOf(EmbeddingException.class);
	}

	@Test
	@DisplayName("게이트웨이 장애는 깨끗한 실패다")
	void 게이트웨이_장애는_깨끗하게_실패한다() {
		응답한다(503, "{\"error\":\"upstream unavailable\"}");

		assertThatThrownBy(() -> 클라이언트().embed(List.of("질문")))
				.as("자동 우회·페일오버 없이 명확히 실패한다 (S8-3)")
				.isInstanceOf(EmbeddingException.class);
	}

	@Test
	@DisplayName("빈 입력은 게이트웨이를 부르지 않는다")
	void 빈_입력은_호출하지_않는다() {
		assertThat(클라이언트().embed(List.of())).isEmpty();
		assertThat(받은_본문.get()).isNull();
	}

	private static String 읽는다(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
