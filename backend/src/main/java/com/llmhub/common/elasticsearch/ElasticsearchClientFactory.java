package com.llmhub.common.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.net.URI;
import java.time.Duration;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.util.Timeout;

/**
 * Elasticsearch 클라이언트를 만든다.
 *
 * <p>운영 배포에서 ES는 보안이 켜져 있고 내부망 전용이다 (SEC-1). 그때는 자격증명이 필요하다. 로컬 개발 compose는 보안이
 * 꺼져 있어 자격증명이 없다.
 *
 * <p>자격증명은 설정값이다. 코드에 하드코딩하지 않는다 (REL-4, SEC-3).
 *
 * <p><b>타임아웃을 명시한다 (REL-1).</b> fluent {@code ElasticsearchTransportConfig}에는 타임아웃 훅이 없고,
 * 기본 응답 타임아웃은 0(무한)이라 ES가 연결만 받고 응답하지 않으면 호출이 매달린다. 그래서 저수준 {@link Rest5Client}에
 * 타임아웃을 건 Apache 비동기 클라이언트를 직접 물린다.
 */
public final class ElasticsearchClientFactory {

	/** 연결 수립 상한. 내부망이라 짧게 잡는다. */
	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/** 응답 대기 상한. 검색·색인 요청이 정상적으로 끝날 시간은 주되 무한 대기는 막는다. */
	private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

	private ElasticsearchClientFactory() {}

	/** 기본 타임아웃으로 만든다. 타임아웃을 신경 쓰지 않는 호출자(테스트 등)를 위한 편의 오버로드다. */
	public static ElasticsearchClient create(String url, String username, String password) {
		return create(url, username, password, DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT);
	}

	/**
	 * @param username 비어 있으면 인증하지 않는다(개발용 compose).
	 * @param connectTimeout 연결 수립 상한.
	 * @param responseTimeout 응답 대기 상한. 없으면(0) 무한 대기다 (REL-1).
	 */
	public static ElasticsearchClient create(
			String url, String username, String password, Duration connectTimeout, Duration responseTimeout) {

		URI uri = URI.create(url);

		RequestConfig requestConfig =
				RequestConfig.custom()
						.setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
						// 서버가 연결만 받고 응답하지 않을 때를 잡는다. 기본값 0은 무한이다 (REL-1).
						.setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
						.build();

		HttpAsyncClientBuilder httpClient = HttpAsyncClients.custom().setDefaultRequestConfig(requestConfig);

		if (username != null && !username.isBlank()) {
			BasicCredentialsProvider credentials = new BasicCredentialsProvider();
			// 이 클라이언트는 설정된 한 호스트만 상대한다. 스코프를 그 호스트로 좁힐 필요가 없어 와일드카드로 둔다.
			credentials.setCredentials(
					new AuthScope(null, -1), new UsernamePasswordCredentials(username, password.toCharArray()));
			httpClient.setDefaultCredentialsProvider(credentials);
		}

		Rest5Client restClient = Rest5Client.builder(uri).setHttpClient(httpClient.build()).build();
		return new ElasticsearchClient(new Rest5ClientTransport(restClient, new JacksonJsonpMapper()));
	}
}
