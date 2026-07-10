package com.llmhub.common.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.nio.file.Path;
import java.time.Instant;
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
						.waitingFor(Wait.forListeningPort());
		컨테이너.start();
		url = "http://" + 컨테이너.getHost() + ":" + 컨테이너.getMappedPort(9200);
		자격증명이_통할_때까지_기다린다();
	}

	/**
	 * HTTP 대기 전략으로는 이 상태를 표현할 수 없다.
	 *
	 * <p>기동 초기의 ES는 {@code /_cluster/health}에 <b>누구에게나</b> 200을 준다. 보안이 아직 켜지지 않았기
	 * 때문이다. 그래서 자격증명을 실어 200을 기다려도 아무것도 증명되지 않는다 — 그 200은 인증을 거치지 않은 것이다. 잠시 뒤
	 * 보안이 켜지고, 그보다 더 뒤에 {@code elastic} 사용자를 담는 네이티브 렘이 준비된다. 그 틈에 인증하면 {@code
	 * unable to authenticate user [elastic]}이다.
	 *
	 * <p>그래서 테스트가 실제로 의존하는 상태를 <b>실제 클라이언트로</b> 기다린다.
	 */
	private static void 자격증명이_통할_때까지_기다린다() throws InterruptedException {
		ElasticsearchClient client = ElasticsearchClientFactory.create(url, "elastic", 비밀번호);
		Instant 마감 = Instant.now().plusSeconds(120);
		Exception 마지막_실패 = null;

		while (Instant.now().isBefore(마감)) {
			try {
				client.cluster().health();
				return;
			} catch (Exception e) {
				마지막_실패 = e;
				Thread.sleep(500);
			}
		}
		throw new IllegalStateException("ES 인증이 준비되지 않았다", 마지막_실패);
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
