package com.llmhub.idx.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.llmhub.idx.service.IndexRequest;
import com.llmhub.idx.service.IndexResult;
import com.llmhub.idx.service.IndexingService;
import com.llmhub.support.PostgresInitializer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * R-13: 업로드 크기 상한을 <b>전체 적재 전에</b> 건다 (SEC-4).
 *
 * <p>상한 검사가 힙에 다 올라온 뒤에 일어나면, 관리자의 거대한 업로드 한 건이 코어를 OOM·디스크 소진으로 죽일 수 있다.
 *
 * <p><b>결정성:</b> 색인 서비스를 <b>검증하지 않는</b> mock으로 둔다. 조인 상한이 없으면 상한 초과 내용이 서비스까지
 * 도달해 200이 되므로, 그 mock이 옛 동작을 드러낸다. 조인 상한이 있으면 서비스에 닿기 전에 400으로 끊긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@Import(IndexControllerTest.대역.class)
@TestPropertySource(properties = "llmhub.idx.max-upload-bytes=50")
class IndexUploadLimitTest {

	@Autowired private WebTestClient client;

	/** 검증하지 않는 색인 서비스. 상한 초과 내용이 여기까지 오면 200이 된다 — 그것을 테스트가 잡는다. */
	@MockitoBean private IndexingService indexingService;

	@Test
	@DisplayName("상한을 넘는 업로드는 색인 서비스에 닿기 전에 400으로 거부된다 (R-13)")
	void 상한_초과_업로드는_서비스_전에_거부된다() {
		when(indexingService.index(any()))
				.thenReturn(new IndexResult(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1));

		byte[] 큰파일 = "가".repeat(200).getBytes(StandardCharsets.UTF_8); // 50바이트 상한보다 훨씬 크다

		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder
				.part(
						"file",
						new ByteArrayResource(큰파일) {
							@Override
							public String getFilename() {
								return "큰파일.txt";
							}
						})
				.contentType(MediaType.TEXT_PLAIN);
		builder.part("docKey", "큰-문서");
		builder.part("accessTags", "public");

		client
				.post()
				.uri("/api/index")
				.header(HttpHeaders.AUTHORIZATION, "Bearer admin")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(builder.build())
				.exchange()
				.expectStatus()
				.isBadRequest();

		verify(indexingService, never().description("상한 초과 내용이 색인 서비스까지 도달하면 안 된다 (R-13)"))
				.index(any(IndexRequest.class));
	}
}
