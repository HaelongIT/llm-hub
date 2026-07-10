package com.llmhub.idx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.idx.chunking.TokenChunkingStrategy;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.idx.index.ElasticsearchChunkRepository;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.support.MinimalHwp;
import com.llmhub.support.MinimalPdf;
import com.llmhub.idx.parser.HwpDocumentParser;
import com.llmhub.idx.parser.TikaDocumentParser;
import com.llmhub.idx.storage.LocalFileStorage;
import com.llmhub.idx.upload.UploadValidator;
import com.llmhub.support.ElasticsearchTestSupport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-IDX 시나리오: PDF 업로드 → 조각들이 ES에 저장되고 메타 7종 존재.
 *
 * <p>대역이 아니라 실제 ES에 쓴다. 매핑·직렬화·nori가 실제로 맞물리는지는 진짜 엔진에서만 드러난다.
 */
class IndexingPipelineIntegrationTest {

	private static final EmbeddingSpec 임베딩 = new EmbeddingSpec("stub-embedding", 4);
	private static final Instant 고정시각 = Instant.parse("2026-07-10T02:00:00Z");
	private static final java.util.UUID 업로더 = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	@DisplayName("PDF를 색인하면 조각이 ES에 저장되고 메타 7종이 모두 존재한다")
	void pdf를_색인하면_ES에_메타_7종과_함께_저장된다(@TempDir Path root) {
		var chunkRepository =
				new ElasticsearchChunkRepository(ElasticsearchTestSupport.client(), "llmhub-chunks-pipeline", "nori");
		var service =
				new IndexingService(
						new UploadValidator(Map.of("pdf", Set.of("application/pdf")), 1_000_000),
						new LocalFileStorage(root),
						List.of(new TikaDocumentParser()),
						new TokenChunkingStrategy(20),
						new StubEmbeddingClient(),
						chunkRepository,
						new InMemoryDocumentRepository(),
						Clock.fixed(고정시각, ZoneOffset.UTC));

		byte[] pdf = MinimalPdf.withText("Annual leave is 15 days per year");
		IndexResult 결과 =
				service.index(new IndexRequest("policy-2026", "policy.pdf", "application/pdf", pdf, List.of("public"), 업로더));

		List<IndexedChunk> 저장된_조각 = chunkRepository.findByDocumentId(결과.documentId());

		assertThat(저장된_조각).hasSize(결과.chunkCount()).isNotEmpty();
		assertThat(저장된_조각).allSatisfy(c -> {
			assertThat(c.documentId()).isEqualTo(결과.documentId());
			assertThat(c.documentName()).isEqualTo("policy.pdf");
			assertThat(c.location()).isNotBlank();
			assertThat(c.accessTags()).containsExactly("public");
			assertThat(c.indexedAt()).isEqualTo(고정시각);
			assertThat(c.embeddingModel()).isEqualTo("stub-embedding");
			assertThat(c.embeddingDim()).isEqualTo(4);
			assertThat(c.indexingRunId()).isEqualTo(결과.indexingRunId());
		});
		assertThat(저장된_조각).extracting(IndexedChunk::text).anySatisfy(t -> assertThat(t).contains("Annual leave"));
	}

	@Test
	@DisplayName("재색인하면 보관된 원본에서 다시 읽어 조각이 통째로 교체된다")
	void 재색인하면_실제_ES의_조각이_교체된다(@TempDir Path root) {
		var chunkRepository =
				new ElasticsearchChunkRepository(ElasticsearchTestSupport.client(), "llmhub-chunks-reindex", "nori");
		var documents = new InMemoryDocumentRepository();
		var service =
				new IndexingService(
						new UploadValidator(Map.of("pdf", Set.of("application/pdf")), 1_000_000),
						new LocalFileStorage(root),
						List.of(new TikaDocumentParser()),
						new TokenChunkingStrategy(20),
						new StubEmbeddingClient(),
						chunkRepository,
						documents,
						Clock.fixed(고정시각, ZoneOffset.UTC));

		byte[] pdf = MinimalPdf.withText("Annual leave is 15 days per year");
		IndexResult 구버전 =
				service.index(new IndexRequest("reindex-me", "policy.pdf", "application/pdf", pdf, List.of("public"), 업로더));

		// 파일을 다시 주지 않는다. doc_key만 준다 (S16).
		IndexResult 신버전 = service.reindex("reindex-me");

		List<IndexedChunk> 저장된_조각 = chunkRepository.findByDocumentId(신버전.documentId());
		assertThat(신버전.documentId()).isEqualTo(구버전.documentId());
		assertThat(신버전.indexingRunId()).isNotEqualTo(구버전.indexingRunId());
		assertThat(저장된_조각).hasSize(구버전.chunkCount());
		assertThat(저장된_조각)
				.as("구버전 조각이 하나라도 남으면 같은 내용이 중복 근거로 나온다 (S17)")
				.allSatisfy(c -> assertThat(c.indexingRunId()).isEqualTo(신버전.indexingRunId()));
		assertThat(저장된_조각)
				.as("메타 7종은 재색인 후에도 그대로다")
				.allSatisfy(c -> {
					assertThat(c.documentName()).isEqualTo("policy.pdf");
					assertThat(c.accessTags()).containsExactly("public");
					assertThat(c.embeddingModel()).isEqualTo("stub-embedding");
					assertThat(c.embeddingDim()).isEqualTo(4);
					assertThat(c.location()).isNotBlank();
					assertThat(c.indexedAt()).isEqualTo(고정시각);
				});
		assertThat(저장된_조각).extracting(IndexedChunk::text).anySatisfy(t -> assertThat(t).contains("Annual leave"));
	}

	@Test
	@DisplayName("hwp를 색인하면 hwplib 경로로 추출되어 한국어 조각이 ES에 저장된다")
	void hwp를_색인하면_hwplib_경로로_저장된다(@TempDir Path root) {
		var chunkRepository =
				new ElasticsearchChunkRepository(ElasticsearchTestSupport.client(), "llmhub-chunks-pipeline", "nori");
		var service =
				new IndexingService(
						new UploadValidator(Map.of("hwp", Set.of("application/x-hwp")), 1_000_000),
						new LocalFileStorage(root),
						// 파서를 하나 더 넣을 뿐, 파이프라인 구조는 그대로다 (E8)
						List.of(new TikaDocumentParser(), new HwpDocumentParser()),
						new TokenChunkingStrategy(20),
						new StubEmbeddingClient(),
						chunkRepository,
						new InMemoryDocumentRepository(),
						Clock.fixed(고정시각, ZoneOffset.UTC));

		byte[] hwp = MinimalHwp.withText("연차휴가는 근로기준법에 따라 부여한다.");
		IndexResult 결과 =
				service.index(
						new IndexRequest("hwp-정책", "인사규정.hwp", "application/x-hwp", hwp, List.of("restricted"), 업로더));

		List<IndexedChunk> 저장된_조각 = chunkRepository.findByDocumentId(결과.documentId());

		assertThat(저장된_조각).isNotEmpty();
		assertThat(저장된_조각).extracting(IndexedChunk::text).anySatisfy(t -> {
			assertThat(t).contains("연차휴가는");
			assertThat(t).as("한국어가 손상되면 안 된다").doesNotContain("�");
		});
		assertThat(저장된_조각).allSatisfy(c -> assertThat(c.accessTags()).containsExactly("restricted"));
	}

	private record StubEmbeddingClient() implements EmbeddingClient {
		@Override
		public EmbeddingSpec spec() {
			return 임베딩;
		}

		@Override
		public List<float[]> embed(List<String> texts) {
			return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
		}
	}

	private static final class InMemoryDocumentRepository implements DocumentRepository {
		private final Map<String, String> idByDocKey = new LinkedHashMap<>();
		private final Map<String, DocumentRecord> byDocKey = new LinkedHashMap<>();

		@Override
		public DocumentRecord upsert(
				String docKey,
				String filename,
				String storageKey,
				List<String> accessTags,
				String embeddingModel,
				String chunkingVersion,
				java.util.UUID uploadedBy) {
			// 서비스가 커밋 전에 DocumentId.of(docKey)로 ES 조각을 조립하므로, 대역도 같은 값을 id로 써야
			// 조각의 document_id와 저장된 document.id가 일치한다 (R-3).
			String id = idByDocKey.computeIfAbsent(docKey, k -> DocumentId.of(k).toString());
			DocumentRecord record = new DocumentRecord(id, docKey, filename, storageKey, accessTags, embeddingModel);
			byDocKey.put(docKey, record);
			return record;
		}

		@Override
		public java.util.Optional<DocumentRecord> findByDocKey(String docKey) {
			return java.util.Optional.ofNullable(byDocKey.get(docKey));
		}

		@Override
		public List<DocumentRecord> findStale(String currentEmbeddingModel) {
			return byDocKey.values().stream().filter(d -> !d.embeddingModel().equals(currentEmbeddingModel)).toList();
		}
	}
}
