package com.llmhub.common.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransportConfig;
import java.net.URI;

/**
 * Elasticsearch 클라이언트를 만든다.
 *
 * <p>운영 배포에서 ES는 보안이 켜져 있고 내부망 전용이다 (SEC-1). 그때는 자격증명이 필요하다. 로컬 개발 compose는 보안이
 * 꺼져 있어 자격증명이 없다.
 *
 * <p>자격증명은 설정값이다. 코드에 하드코딩하지 않는다 (REL-4, SEC-3).
 */
public final class ElasticsearchClientFactory {

	private ElasticsearchClientFactory() {}

	/**
	 * @param username 비어 있으면 인증하지 않는다(개발용 compose).
	 */
	public static ElasticsearchClient create(String url, String username, String password) {
		ElasticsearchTransportConfig.Builder config =
				new ElasticsearchTransportConfig.Builder().host(URI.create(url));

		if (username != null && !username.isBlank()) {
			config.usernameAndPassword(username, password);
		}

		return new ElasticsearchClient(config.build());
	}
}
