package com.llmhub.idx.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmhub.idx.chunking.TokenChunkingStrategy;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.idx.index.EmbeddedChunk;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.idx.parser.TikaDocumentParser;
import com.llmhub.idx.storage.LocalFileStorage;
import com.llmhub.idx.upload.UploadRejectedException;
import com.llmhub.idx.upload.UploadValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-IDX 시나리오: txt 업로드 → 색인 성공. 색인 임베딩 모델 = 설정값.
 *
 * <p>S1: 색인 로직은 진입 경로(API/CLI/UI)와 분리된 서비스 계층에 있다 (E1). 이 테스트는 컨트롤러 없이 서비스를 직접
 * 부른다.
 *
 * <p>외부 의존(ES, 임베딩 게이트웨이)은 인터페이스로 추상화되어 여기서 대체된다 (MAINT-2).
 */
class IndexingServiceTest {

	private static final EmbeddingSpec 설정된_임베딩 = new EmbeddingSpec("bge-m3", 4);
	private static final Instant 고정시각 = Instant.parse("2026-07-10T02:00:00Z");

	private static final byte[] 본문 =
			("연차휴가는 근로기준법에 따라 부여한다. 1년간 80퍼센트 이상 출근한 근로자에게 15일의 유급휴가를 준다. "
							+ "계속하여 근로한 기간이 1년 미만인 근로자에게는 1개월 개근 시 1일의 유급휴가를 준다.")
					.getBytes(UTF_8);

	private FakeChunkRepository chunks;
	private FakeDocumentRepository documents;
	private IndexingService service;

	private void 준비한다(Path root, EmbeddingClient embeddingClient) {
		chunks = new FakeChunkRepository();
		documents = new FakeDocumentRepository();
		service =
				new IndexingService(
						new UploadValidator(Map.of("txt", Set.of("text/plain")), 1_000_000),
						new LocalFileStorage(root),
						List.of(new TikaDocumentParser()),
						new TokenChunkingStrategy(20),
						embeddingClient,
						chunks,
						documents,
						Clock.fixed(고정시각, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("txt를 색인하면 조각이 저장되고 메타 7종이 채워진다")
	void txt를_색인한다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));

		IndexResult 결과 = service.index(요청("인사규정-2026", "인사규정.txt", List.of("public")));

		assertThat(결과.chunkCount()).isGreaterThan(1);
		assertThat(chunks.indexed).hasSize(결과.chunkCount());
		assertThat(chunks.indexed).allSatisfy(e -> {
			IndexedChunk c = e.chunk();
			assertThat(c.documentId()).isEqualTo(결과.documentId());
			assertThat(c.documentName()).isEqualTo("인사규정.txt");
			assertThat(c.location()).isNotBlank();
			assertThat(c.accessTags()).containsExactly("public");
			assertThat(c.indexedAt()).isEqualTo(고정시각);
			assertThat(c.indexingRunId()).isEqualTo(결과.indexingRunId());
		});
	}

	@Test
	@DisplayName("색인에 쓴 임베딩 모델과 차원은 설정값이다")
	void 임베딩_모델은_설정값이다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));

		service.index(요청("규정", "규정.txt", List.of("public")));

		assertThat(chunks.indexed).allSatisfy(e -> {
			assertThat(e.chunk().embeddingModel()).isEqualTo("bge-m3");
			assertThat(e.chunk().embeddingDim()).isEqualTo(4);
			assertThat(e.embedding()).hasSize(4);
		});
	}

	@Test
	@DisplayName("원본이 보관되고 document 레코드가 그 경로를 가리킨다")
	void 원본이_보관된다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));

		IndexResult 결과 = service.index(요청("규정", "규정.txt", List.of("public")));

		DocumentRecord 문서 = documents.byId.get(결과.documentId());
		assertThat(문서.storageKey()).isNotBlank();
		assertThat(new LocalFileStorage(root).read(문서.storageKey())).isEqualTo(본문);
	}

	@Test
	@DisplayName("허용되지 않은 확장자는 색인 이전에 거부된다")
	void 허용되지_않은_확장자를_거부한다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));

		assertThatThrownBy(() -> service.index(요청("악성", "악성.exe", List.of("public"))))
				.isInstanceOf(UploadRejectedException.class);

		assertThat(chunks.indexed).as("거부된 업로드는 아무것도 남기지 않는다").isEmpty();
		assertThat(documents.byId).isEmpty();
	}

	@Test
	@DisplayName("임베딩 단계가 실패하면 조각이 하나도 색인되지 않는다")
	void 임베딩_실패시_색인하지_않는다(@TempDir Path root) {
		준비한다(root, new FailingEmbeddingClient(설정된_임베딩));

		assertThatThrownBy(() -> service.index(요청("규정", "규정.txt", List.of("public"))))
				.isInstanceOf(IllegalStateException.class);

		assertThat(chunks.indexed)
				.as("임베딩을 전량 마친 뒤에 bulk 색인한다. 부분 색인된 조각이 남으면 안 된다 (S17 x S8-3)")
				.isEmpty();
	}

	@Test
	@DisplayName("같은 doc_key로 재색인하면 구버전 조각이 사라지고 신버전만 남는다")
	void 재색인하면_구버전_조각이_사라진다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));

		IndexResult 구버전 = service.index(요청("인사규정-2026", "인사규정.txt", List.of("public")));
		IndexResult 신버전 = service.index(요청("인사규정-2026", "인사규정.txt", List.of("public")));

		assertThat(신버전.documentId()).as("같은 doc_key는 같은 document다 (S17)").isEqualTo(구버전.documentId());
		assertThat(신버전.indexingRunId()).isNotEqualTo(구버전.indexingRunId());
		assertThat(chunks.indexed)
				.as("구버전 조각이 남으면 같은 내용이 중복 근거로 나온다")
				.allSatisfy(e -> assertThat(e.chunk().indexingRunId()).isEqualTo(신버전.indexingRunId()));
	}

	@Test
	@DisplayName("구버전 삭제는 신버전 색인이 끝난 뒤에 일어난다")
	void 삭제는_색인_이후에_일어난다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));
		service.index(요청("규정", "규정.txt", List.of("public")));

		service.index(요청("규정", "규정.txt", List.of("public")));

		assertThat(chunks.순서)
				.as("삭제가 먼저 일어나면 색인 실패 시 문서가 증발한다 (S17 x S8-3)")
				.containsSubsequence("indexAll", "deleteStale");
	}

	@Test
	@DisplayName("재색인 중 임베딩이 실패하면 구버전 조각이 그대로 검색된다")
	void 재색인_실패시_구버전이_살아남는다(@TempDir Path root) {
		준비한다(root, new FakeEmbeddingClient(설정된_임베딩));
		IndexResult 구버전 = service.index(요청("규정", "규정.txt", List.of("public")));
		int 구버전_조각수 = chunks.indexed.size();

		service =
				new IndexingService(
						new UploadValidator(Map.of("txt", Set.of("text/plain")), 1_000_000),
						new LocalFileStorage(root),
						List.of(new TikaDocumentParser()),
						new TokenChunkingStrategy(20),
						new FailingEmbeddingClient(설정된_임베딩),
						chunks,
						documents,
						Clock.fixed(고정시각, ZoneOffset.UTC));

		assertThatThrownBy(() -> service.index(요청("규정", "규정.txt", List.of("public"))))
				.isInstanceOf(IllegalStateException.class);

		assertThat(chunks.indexed)
				.as("재색인 중 장애가 나도 문서가 증발하면 안 된다 (S17 x S8-3)")
				.hasSize(구버전_조각수)
				.allSatisfy(e -> assertThat(e.chunk().indexingRunId()).isEqualTo(구버전.indexingRunId()));
	}

	@Test
	@DisplayName("지원하는 파서가 없으면 거부한다")
	void 파서가_없으면_거부한다(@TempDir Path root) {
		chunks = new FakeChunkRepository();
		documents = new FakeDocumentRepository();
		service =
				new IndexingService(
						new UploadValidator(Map.of("hwp", Set.of("application/x-hwp")), 1_000_000),
						new LocalFileStorage(root),
						List.of(new TikaDocumentParser()), // hwp를 다루는 어댑터가 없다
						new TokenChunkingStrategy(20),
						new FakeEmbeddingClient(설정된_임베딩),
						chunks,
						documents,
						Clock.fixed(고정시각, ZoneOffset.UTC));

		assertThatThrownBy(
						() ->
								service.index(
										new IndexRequest("문서", "문서.hwp", "application/x-hwp", 본문, List.of("public"))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hwp");
	}

	private static IndexRequest 요청(String docKey, String filename, List<String> tags) {
		return new IndexRequest(docKey, filename, "text/plain", 본문, tags);
	}

	// ─── 테스트 대역 ───

	private record FakeEmbeddingClient(EmbeddingSpec spec) implements EmbeddingClient {
		@Override
		public List<float[]> embed(List<String> texts) {
			return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
		}
	}

	private record FailingEmbeddingClient(EmbeddingSpec spec) implements EmbeddingClient {
		@Override
		public List<float[]> embed(List<String> texts) {
			throw new IllegalStateException("임베딩 게이트웨이 장애");
		}
	}

	private static final class FakeChunkRepository implements com.llmhub.idx.index.ChunkRepository {
		private final java.util.List<EmbeddedChunk> indexed = new java.util.ArrayList<>();
		/** 호출 순서를 기록한다. S17은 "무엇을 했나"가 아니라 "어떤 순서로 했나"의 문제다. */
		private final java.util.List<String> 순서 = new java.util.ArrayList<>();

		@Override
		public void createIndexIfMissing(EmbeddingSpec spec) {}

		@Override
		public void indexAll(List<EmbeddedChunk> batch) {
			순서.add("indexAll");
			indexed.addAll(batch);
		}

		@Override
		public void deleteStaleChunks(String documentId, String currentIndexingRunId) {
			순서.add("deleteStale");
			indexed.removeIf(
					e ->
							e.chunk().documentId().equals(documentId)
									&& !e.chunk().indexingRunId().equals(currentIndexingRunId));
		}

		@Override
		public List<IndexedChunk> findByDocumentId(String documentId) {
			return indexed.stream().map(EmbeddedChunk::chunk).filter(c -> c.documentId().equals(documentId)).toList();
		}
	}

	private static final class FakeDocumentRepository implements DocumentRepository {
		private final Map<String, DocumentRecord> byId = new java.util.LinkedHashMap<>();
		private final Map<String, String> idByDocKey = new java.util.LinkedHashMap<>();

		@Override
		public DocumentRecord upsert(
				String docKey, String filename, String storageKey, List<String> accessTags, String embeddingModel) {
			String id = idByDocKey.computeIfAbsent(docKey, k -> "doc-" + (byId.size() + 1));
			DocumentRecord record =
					new DocumentRecord(id, docKey, filename, storageKey, List.copyOf(accessTags), embeddingModel);
			byId.put(id, record);
			return record;
		}
	}
}
