package com.llmhub.idx.api;

import com.llmhub.common.Blocking;
import com.llmhub.idx.service.IndexRequest;
import com.llmhub.idx.service.IndexResult;
import com.llmhub.idx.service.IndexingService;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 관리자 색인 API. 문서는 이 경로로만 들어온다 (S1).
 *
 * <p>ADMIN 역할만 호출할 수 있다. 그 판정은 여기가 아니라 필터체인에서 한다 (SEC-2, S4). 컨트롤러는 권한을 재판단하지
 * 않는다.
 *
 * <p>색인은 동기 처리다. 진행률·큐는 없고, 실패하면 에러 응답이다 (S1, PERF-4).
 *
 * <p><b>색인 파이프라인은 블로킹이다.</b> 파일 I/O·JPA·ES 호출이 섞여 있다. 논블로킹 컨트롤러가 그것을 격리 스케줄러로
 * 넘기지 않으면 Netty 이벤트 루프가 굶는다 (S13, PERF-1).
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

	private final IndexingService indexingService;

	public IndexController(IndexingService indexingService) {
		this.indexingService = indexingService;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<IndexResult> index(
			@RequestPart("file") FilePart file,
			@RequestPart("docKey") String docKey,
			@RequestPart("accessTags") String accessTags) {

		MediaType contentType = file.headers().getContentType();
		List<String> tags = Arrays.stream(accessTags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

		return DataBufferUtils.join(file.content())
				.map(IndexController::toBytes)
				.flatMap(
						content ->
								Blocking.call(
										() ->
												indexingService.index(
														new IndexRequest(
																docKey,
																file.filename(),
																contentType == null ? "" : contentType.toString(),
																content,
																tags))));
	}

	private static byte[] toBytes(DataBuffer buffer) {
		byte[] bytes = new byte[buffer.readableByteCount()];
		buffer.read(bytes);
		DataBufferUtils.release(buffer);
		return bytes;
	}
}
