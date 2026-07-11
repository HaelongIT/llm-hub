package com.llmhub.idx.api;

import com.llmhub.common.Blocking;
import com.llmhub.common.user.AppUserRepository;
import com.llmhub.idx.service.IndexRequest;
import com.llmhub.idx.service.IndexResult;
import com.llmhub.idx.service.IndexingService;
import com.llmhub.idx.service.StaleDocument;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
	private final AppUserRepository userRepository;
	private final int maxUploadBytes;

	public IndexController(
			IndexingService indexingService,
			AppUserRepository userRepository,
			com.llmhub.idx.config.IdxProperties properties) {
		this.indexingService = indexingService;
		this.userRepository = userRepository;
		this.maxUploadBytes = (int) properties.maxUploadBytes();
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<IndexResult> index(
			@RequestPart("file") FilePart file,
			@RequestPart("docKey") String docKey,
			@RequestPart("accessTags") String accessTags,
			@AuthenticationPrincipal Jwt jwt) {

		MediaType contentType = file.headers().getContentType();
		List<String> tags = Arrays.stream(accessTags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

		// 상한을 조인 단계에 건다. 안 걸면 거대한 업로드가 전부 힙에 올라온 뒤에야 검증기가 거부해
		// OOM·디스크 소진으로 코어가 죽을 수 있다 (SEC-4). 초과 시 DataBufferLimitException.
		return DataBufferUtils.join(file.content(), maxUploadBytes)
				.map(IndexController::toBytes)
				// 내용이 한 조각도 오지 않으면 join()은 빈 Mono다. 그대로 두면 flatMap이 통째로 건너뛰어지고
				// 핸들러가 아무 값도 내지 않는다 — 색인은 없었는데 응답은 200이다. 빈 바이트로 내려보내
				// 검증기가 거부하게 한다 (SEC-4).
				.defaultIfEmpty(new byte[0])
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
																tags,
																// 누가 올렸는지 document에 남긴다 (docs/03). 권한 판단이 아니라 신원 확인이다.
																userRepository.ensureExists(jwt.getSubject())))))
				// 크기 초과는 클라이언트 잘못이다. 검증기의 거부와 같은 400으로 맞춘다 (SEC-4, SEC-3).
				.onErrorMap(
						org.springframework.core.io.buffer.DataBufferLimitException.class,
						e -> new com.llmhub.idx.upload.UploadRejectedException("업로드 크기 상한 초과"));
	}

	/**
	 * 보관된 원본에서 다시 색인한다 (S16). 파일을 받지 않는다.
	 *
	 * <p>{@code docKey}를 경로 변수가 아니라 쿼리 파라미터로 받는다. 업로드 때 사용자가 정하는 임의의 문자열이라 {@code
	 * /}가 들어갈 수 있고, 그러면 경로 변수로는 주소를 만들 수 없다.
	 */
	@PostMapping("/reindex")
	public Mono<IndexResult> reindex(@RequestParam("docKey") String docKey) {
		return Blocking.call(() -> indexingService.reindex(docKey));
	}

	/** 현재 설정과 다른 임베딩 모델로 색인된 문서들. 운영자가 하나씩 재색인한다 (E9). */
	@GetMapping("/stale")
	public Mono<List<StaleDocument>> stale() {
		return Blocking.call(indexingService::staleDocuments);
	}

	private static byte[] toBytes(DataBuffer buffer) {
		// 읽는 도중 예외가 나도 버퍼를 반드시 해제한다. finally가 없으면 그 경로에서 조인 버퍼가 샌다 (L-2).
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		} finally {
			DataBufferUtils.release(buffer);
		}
	}
}
