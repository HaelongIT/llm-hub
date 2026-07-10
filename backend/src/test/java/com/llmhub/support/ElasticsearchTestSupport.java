package com.llmhub.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.net.URI;
import java.nio.file.Path;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * nori가 구워진 ES 컨테이너를 JVM 당 한 번만 띄운다 (Testcontainers 싱글턴 패턴).
 *
 * <p>compose와 <b>같은 Dockerfile</b>로 빌드한다. 통합 테스트가 다른 이미지를 쓰면 "색인과 검색이 같은 분석기를
 * 쓴다"는 보장이 사라진다.
 *
 * <p>컨테이너를 명시적으로 stop하지 않는다. Testcontainers의 Ryuk이 JVM 종료 시 정리한다.
 */
public final class ElasticsearchTestSupport {

	private static final ElasticsearchContainer CONTAINER;
	private static final ElasticsearchClient CLIENT;

	static {
		Path dockerfile = Path.of("..", "docker", "elasticsearch", "Dockerfile").toAbsolutePath().normalize();
		String image;
		try {
			image = new ImageFromDockerfile("llmhub-es-nori", false).withDockerfile(dockerfile).get();
		} catch (Exception e) {
			throw new IllegalStateException("nori ES 이미지 빌드 실패: " + dockerfile, e);
		}

		CONTAINER =
				new ElasticsearchContainer(
						DockerImageName.parse(image)
								.asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
						.withEnv("xpack.security.enabled", "false")
						.withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g");
		CONTAINER.start();

		Rest5Client lowLevel = Rest5Client.builder(URI.create("http://" + CONTAINER.getHttpHostAddress())).build();
		CLIENT = new ElasticsearchClient(new Rest5ClientTransport(lowLevel, new JacksonJsonpMapper()));
	}

	private ElasticsearchTestSupport() {}

	public static ElasticsearchClient client() {
		return CLIENT;
	}
}
