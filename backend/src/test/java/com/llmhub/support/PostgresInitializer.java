package com.llmhub.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너를 JVM당 한 번 띄우고 datasource 속성을 주입한다.
 *
 * <p>JPA·Flyway가 클래스패스에 있으면 애플리케이션 컨텍스트는 기동 시 DB에 연결한다. 그래서 웹 테스트를 포함한 모든
 * {@code @SpringBootTest}가 실제 DB를 필요로 한다. 가짜로 우회하는 대신 진짜를 띄운다 — 그것이 REL-5(배포
 * 재현성)가 요구하는 것이기도 하다.
 *
 * <p>{@code spring-boot-testcontainers}의 {@code @ServiceConnection}을 쓰지 않는다. 사전 승인된
 * 의존성 목록 밖이다 (docs/08 §E).
 */
public final class PostgresInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final PostgreSQLContainer<?> CONTAINER;

	static {
		CONTAINER = new PostgreSQLContainer<>("postgres:17-alpine");
		CONTAINER.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of(
						"spring.datasource.url=" + CONTAINER.getJdbcUrl(),
						"spring.datasource.username=" + CONTAINER.getUsername(),
						"spring.datasource.password=" + CONTAINER.getPassword())
				.applyTo(context.getEnvironment());
	}
}
