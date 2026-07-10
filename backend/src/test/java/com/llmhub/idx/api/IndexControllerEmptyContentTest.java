package com.llmhub.idx.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.common.user.AppUserRepository;
import com.llmhub.idx.chunking.TokenChunkingStrategy;
import com.llmhub.idx.index.ChunkRepository;
import com.llmhub.idx.index.EmbeddedChunk;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.idx.parser.TikaDocumentParser;
import com.llmhub.idx.service.DocumentRecord;
import com.llmhub.idx.service.DocumentRepository;
import com.llmhub.idx.service.IndexingService;
import com.llmhub.idx.storage.LocalFileStorage;
import com.llmhub.idx.upload.UploadRejectedException;
import com.llmhub.idx.upload.UploadValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 내용이 <b>한 조각도 오지 않는</b> 파일 파트를 컨트롤러가 어떻게 다루는가.
 *
 * <p>{@code DataBufferUtils.join}은 소스 Flux가 비어 있으면 <b>빈 Mono</b>를 낸다. 그러면 {@code
 * flatMap}이 통째로 건너뛰어지고 핸들러는 아무 값도 내지 않는다 — 색인은 일어나지 않았는데 응답은 <b>200</b>이다.
 * 관리자는 문서가 들어갔다고 믿는다.
 *
 * <p>실제로 이 상태를 만드는 클라이언트를 우리가 통제할 수 없다. 0바이트 업로드에서 curl은 파트를 아예 빼버리고(프레임워크가
 * 400), WebTestClient는 길이 0 버퍼를 보낸다(검증기가 400). 인코더 동작에 기대는 대신 구조로 막는다 (SEC-4).
 */
class IndexControllerEmptyContentTest {

	@Test
	@DisplayName("내용이 비어 있는 파트는 조용히 200이 되지 않고 거부된다")
	void 내용이_없는_파트는_거부된다(@TempDir Path root) {
		IndexController controller = new IndexController(색인_서비스(root), 대역_사용자저장소());

		Mono<?> 응답 = controller.index(내용이_비어있는_파트(), "빈-문서", "public", 대역_토큰());

		StepVerifier.create(응답)
				.expectErrorSatisfies(
						e ->
								assertThat(e)
										.as("빈 Mono가 되어 200으로 나가면 색인되지 않은 문서를 색인됐다고 보고하는 셈이다")
										.isInstanceOf(UploadRejectedException.class))
				.verify();
	}

	/** 파트는 존재하는데 내용 Flux가 비어 있다. {@code join()}이 빈 Mono를 낸다. */
	private static FilePart 내용이_비어있는_파트() {
		return new FilePart() {
			@Override
			public String filename() {
				return "빈파일.txt";
			}

			@Override
			public Mono<Void> transferTo(Path dest) {
				return Mono.empty();
			}

			@Override
			public String name() {
				return "file";
			}

			@Override
			public HttpHeaders headers() {
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
				return headers;
			}

			@Override
			public Flux<DataBuffer> content() {
				return Flux.empty();
			}
		};
	}

	private static IndexingService 색인_서비스(Path root) {
		return new IndexingService(
				new UploadValidator(Map.of("txt", Set.of("text/plain")), 1_000_000),
				new LocalFileStorage(root),
				List.of(new TikaDocumentParser()),
				new TokenChunkingStrategy(20),
				대역_임베딩(),
				대역_조각저장소(),
				대역_문서저장소(),
				Clock.fixed(Instant.parse("2026-07-10T02:00:00Z"), ZoneOffset.UTC));
	}

	private static AppUserRepository 대역_사용자저장소() {
		return subject -> UUID.fromString("11111111-1111-1111-1111-111111111111");
	}

	private static org.springframework.security.oauth2.jwt.Jwt 대역_토큰() {
		Instant now = Instant.now();
		return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
				.header("alg", "RS256")
				.subject("subject-ADMIN")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(3600))
				.build();
	}

	private static EmbeddingClient 대역_임베딩() {
		return new EmbeddingClient() {
			@Override
			public EmbeddingSpec spec() {
				return new EmbeddingSpec("stub", 4);
			}

			@Override
			public List<float[]> embed(List<String> texts) {
				throw new AssertionError("빈 파일은 임베딩까지 가면 안 된다");
			}
		};
	}

	private static ChunkRepository 대역_조각저장소() {
		return new ChunkRepository() {
			@Override
			public OptionalInt indexedDimensions() {
				return OptionalInt.empty();
			}

			@Override
			public void createIndexIfMissing(EmbeddingSpec spec) {}

			@Override
			public void indexAll(List<EmbeddedChunk> chunks) {
				throw new AssertionError("빈 파일은 색인되면 안 된다");
			}

			@Override
			public void deleteStaleChunks(String documentId, String currentIndexingRunId) {}

			@Override
			public List<IndexedChunk> findByDocumentId(String documentId) {
				return List.of();
			}
		};
	}

	private static DocumentRepository 대역_문서저장소() {
		return new DocumentRepository() {
			@Override
			public DocumentRecord upsert(
					String docKey,
					String filename,
					String storageKey,
					List<String> accessTags,
					String embeddingModel,
					String chunkingVersion,
					UUID uploadedBy) {
				throw new AssertionError("빈 파일은 document를 만들면 안 된다");
			}

			@Override
			public Optional<DocumentRecord> findByDocKey(String docKey) {
				return Optional.empty();
			}

			@Override
			public List<DocumentRecord> findStale(String currentEmbeddingModel) {
				return List.of();
			}
		};
	}
}
