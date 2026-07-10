package com.llmhub.auth;

import java.net.InetSocketAddress;
import org.springframework.core.env.Environment;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청이 <b>관리 포트</b>로 들어왔는가 (REL-5).
 *
 * <p>관리 포트를 분리해도 보안 필터체인은 두 포트 모두에 걸린다. Spring Boot의 리액티브 관리 컨텍스트가 부모 컨텍스트의
 * {@code WebFilter}를 그대로 쓰기 때문이다(실측: 관리 포트 응답에 {@code WWW-Authenticate: Bearer}가
 * 붙는다). 그래서 헬스 엔드포인트를 열려면 <b>포트로 범위를 좁힌</b> 체인이 필요하다.
 *
 * <p>경로만으로 열면 애플리케이션 포트의 헬스까지 함께 열려 SEC-1("모든 API는 인증 필요")이 깨진다.
 *
 * <p>관리 포트가 설정되지 않았거나 애플리케이션 포트와 같으면 <b>매치하지 않는다</b>. 설정 실수로 헬스가 외부에 노출되는
 * 경로를 만들지 않는다.
 */
final class ManagementPortMatcher implements ServerWebExchangeMatcher {

	private final Environment environment;

	ManagementPortMatcher(Environment environment) {
		this.environment = environment;
	}

	@Override
	public Mono<MatchResult> matches(ServerWebExchange exchange) {
		InetSocketAddress local = exchange.getRequest().getLocalAddress();
		// 서버가 실제로 바인딩한 포트다. management.server.port=0(테스트)에서도 실제 값이 들어온다.
		Integer management = port("local.management.port");
		Integer application = port("local.server.port");

		if (local == null || management == null || management.equals(application)) {
			return MatchResult.notMatch();
		}
		return local.getPort() == management ? MatchResult.match() : MatchResult.notMatch();
	}

	private Integer port(String property) {
		return environment.getProperty(property, Integer.class);
	}
}
