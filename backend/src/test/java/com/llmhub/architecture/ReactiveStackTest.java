package com.llmhub.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/**
 * S13(리액티브-블로킹 격리)과 PERF-1(논블로킹 무결성)의 전제를 지킨다.
 *
 * <p>spring-boot-starter-web이 클래스패스에 섞여 들어오면 Spring Boot는 경고 없이
 * WebFlux 대신 MVC를 자동설정한다. 그러면 애플리케이션은 정상 기동하지만
 * 논블로킹 스택이 아니게 되고, 블로킹 격리 논의 전체가 무의미해진다.
 * 이 테스트는 그 사고를 기동 시점에 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReactiveStackTest {

	@org.springframework.beans.factory.annotation.Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("애플리케이션은 리액티브 컨텍스트에서 Netty로 기동한다 (MVC 자동설정으로 조용히 넘어가지 않는다)")
	void 리액티브_스택으로_기동한다() {
		assertThat(context)
				.as("서블릿 스타터가 섞이면 컨텍스트가 리액티브가 아니게 된다")
				.isInstanceOf(ReactiveWebServerApplicationContext.class);

		var webServer = ((ReactiveWebServerApplicationContext) context).getWebServer();
		assertThat(webServer)
				.as("논블로킹 서버는 Netty여야 한다. Tomcat이면 서블릿 스택으로 넘어간 것이다")
				.isInstanceOf(NettyWebServer.class);
	}
}
