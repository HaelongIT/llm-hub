package com.llmhub.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.support.PostgresInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * REL-5: 컨테이너 오케스트레이터가 코어의 기동 완료를 알 수 있어야 한다. 그래야 BFF가 코어보다 먼저 뜨는 일이 없다.
 *
 * <p>그런데 SEC-1은 "모든 API는 인증 필요"다. 헬스 엔드포인트를 애플리케이션 포트에 열면 그 문구가 깨진다. 그래서
 * <b>관리 포트를 분리</b>한다. 관리 포트는 호스트에 publish하지 않으므로 컨테이너 밖에서 보이지 않고, 애플리케이션 포트의
 * API 표면은 여전히 전부 인증된다.
 *
 * <p>"관리 포트에는 보안 필터체인이 걸리지 않는다"는 Spring Boot의 동작이다. 문서로 믿지 않고 여기서 실측한다.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "management.server.port=0")
@ContextConfiguration(initializers = PostgresInitializer.class)
class ActuatorHealthTest {

	/** 애플리케이션 포트에 바인딩된다. */
	@Autowired private WebTestClient client;

	@Autowired private Environment environment;

	@Test
	@DisplayName("관리 포트의 헬스 엔드포인트는 인증 없이 200이다")
	void 관리_포트의_헬스는_인증이_필요_없다() {
		관리_포트_클라이언트()
				.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo("UP");
	}

	@Test
	@DisplayName("관리 포트는 애플리케이션 포트와 다르다")
	void 관리_포트가_분리되어_있다() {
		assertThat(관리_포트()).isNotEqualTo(environment.getProperty("local.server.port"));
	}

	@Test
	@DisplayName("애플리케이션 포트에는 액추에이터가 열려 있지 않다")
	void 애플리케이션_포트는_전부_인증을_요구한다() {
		// SEC-1. 관리 포트를 분리해도 애플리케이션 포트에 헬스가 남아 있으면 의미가 없다.
		client.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
	}

	private WebTestClient 관리_포트_클라이언트() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + 관리_포트()).build();
	}

	private String 관리_포트() {
		return environment.getProperty("local.management.port");
	}
}
