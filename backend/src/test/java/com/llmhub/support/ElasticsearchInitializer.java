package com.llmhub.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 애플리케이션이 테스트용 ES 컨테이너를 바라보게 한다.
 *
 * <p>색인 인덱스와 검색 인덱스를 <b>같은 이름</b>으로 준다. 둘이 갈라지면 IDX와 SEARCH가 서로 다른 데이터를 보게 되고,
 * 그 사실이 조용히 통과한다.
 */
public final class ElasticsearchInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final String INDEX = "llmhub-e2e";

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of(
						"llmhub.idx.elasticsearch-url=" + ElasticsearchTestSupport.httpUrl(),
						"llmhub.idx.index-name=" + INDEX,
						"llmhub.search.index-name=" + INDEX)
				.applyTo(context.getEnvironment());
	}
}
