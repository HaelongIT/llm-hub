package com.llmhub.common.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * 운영 배포에서 Elasticsearch는 보안이 켜져 있다 (SEC-1). 그러면 백엔드가 자격증명을 넘겨야 한다.
 *
 * <p>보안이 켜진 ES를 실제로 띄워 확인한다. "설정을 넣었다"와 "인증이 실제로 걸린다"는 다르다 — 자격증명 없는 클라이언트가
 * <b>거부되는지</b>를 먼저 본다. 거부되지 않는다면 이 테스트는 아무것도 증명하지 못한다.
 */
class ElasticsearchAuthTest {

	private static final String 비밀번호 = "test-elastic-password";

	private static GenericContainer<?> 컨테이너;
	private static String url;

	@BeforeAll
	static void 보안이_켜진_ES를_띄운다() throws Exception {
		Path dockerfile = Path.of("..", "docker", "elasticsearch", "Dockerfile").toAbsolutePath().normalize();
		String image = new ImageFromDockerfile("llmhub-es-nori", false).withDockerfile(dockerfile).get();

		컨테이너 =
				new GenericContainer<>(image)
						.withEnv("discovery.type", "single-node")
						.withEnv("xpack.security.enabled", "true")
						// TLS는 이 테스트의 관심사가 아니다. 인증이 걸리는지만 본다.
						.withEnv("xpack.security.http.ssl.enabled", "false")
						.withEnv("xpack.security.transport.ssl.enabled", "false")
						.withEnv("ELASTIC_PASSWORD", 비밀번호)
						.withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g")
						.withExposedPorts(9200)
						.waitingFor(
								Wait.forHttp("/_cluster/health")
										.forPort(9200)
										// 보안이 켜졌으면 자격증명 없는 요청은 401이다. 그것도 "떴다"는 신호다.
										.forStatusCodeMatching(code -> code == 200 || code == 401));
		컨테이너.start();
		url = "http://" + 컨테이너.getHost() + ":" + 컨테이너.getMappedPort(9200);
	}

	@AfterAll
	static void 내린다() {
		if (컨테이너 != null) {
			컨테이너.stop();
		}
	}

	@Test
	@DisplayName("자격증명이 없으면 거부된다 — 인증이 실제로 걸려 있다")
	void 자격증명이_없으면_거부된다() {
		ElasticsearchClient client = ElasticsearchClientFactory.create(url, null, null);

		assertThatThrownBy(() -> client.cluster().health())
				.as("이게 통과하면 보안이 꺼진 것이고, 아래 테스트는 아무것도 증명하지 못한다")
				.isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("자격증명이 있으면 접속된다")
	void 자격증명이_있으면_접속된다() throws Exception {
		ElasticsearchClient client = ElasticsearchClientFactory.create(url, "elastic", 비밀번호);

		assertThat(client.cluster().health().clusterName()).isNotBlank();
	}

	@Test
	@DisplayName("비밀번호가 틀리면 거부된다")
	void 틀린_비밀번호는_거부된다() {
		ElasticsearchClient client = ElasticsearchClientFactory.create(url, "elastic", "wrong-password");

		assertThatThrownBy(() -> client.cluster().health()).isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("사용자명이 비어 있으면 인증 없이 접속한다 (개발용 compose)")
	void 사용자명이_비면_인증하지_않는다() {
		// 로컬 개발 compose는 보안이 꺼져 있다. 빈 자격증명으로도 클라이언트가 만들어져야 한다.
		assertThat(ElasticsearchClientFactory.create(url, "", "")).isNotNull();
		assertThat(ElasticsearchClientFactory.create(url, null, null)).isNotNull();
	}
}
