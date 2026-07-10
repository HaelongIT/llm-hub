package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.TraceId;
import com.llmhub.support.PostgresInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * REL-3: 추적 ID는 <b>모든</b> 요청에 붙는다. 인증에 실패한 요청도 포함한다.
 *
 * <p>401의 원인을 추적하려면 거부된 요청에도 ID가 있어야 한다. 그래서 추적 필터는 필터체인의 맨 앞(FIRST)에 선다 —
 * 인증·인가보다 먼저 실행되고, 그들이 응답을 쓰기 전에 헤더를 얹는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
class TraceIdHeaderTest {

	@Autowired private WebTestClient client;

	@Test
	@DisplayName("인증에 실패한 401 응답에도 추적 ID가 붙는다")
	void 인증_실패_응답에도_추적_ID가_붙는다() {
		client
				.get()
				.uri("/api/sessions")
				.exchange()
				.expectStatus()
				.isUnauthorized()
				.expectHeader()
				.value(TraceId.HEADER, id -> assertThat(id).as("거부된 요청의 원인도 추적할 수 있어야 한다").isNotBlank());
	}
}
