package com.llmhub.idx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.idx.chunking.TokenChunkingStrategy;
import com.llmhub.idx.embedding.EmbeddingClient;
import com.llmhub.idx.index.ElasticsearchChunkRepository;
import com.llmhub.idx.index.EmbeddingSpec;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.support.MinimalPdf;
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

	@Test
	@DisplayName("PDF를 색인하면 조각이 ES에 저장되고 메타 7종이 모두 존재한다")
	void pdf를_색인하면_ES에_메타_7종과_함께_저장된다(@TempDir Path root) {
		var chunkRepository =
				new ElasticsearchChunkRepository(ElasticsearchTestSupport.client(), "llmhub-chunks-pipeline");
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
				service.index(new IndexRequest("policy-2026", "policy.pdf", "application/pdf", pdf, List.of("public")));

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

		@Override
		public DocumentRecord upsert(
				String docKey, String filename, String storageKey, List<String> accessTags, String embeddingModel) {
			String id = idByDocKey.computeIfAbsent(docKey, k -> "doc-" + (idByDocKey.size() + 1));
			return new DocumentRecord(id, docKey, filename, storageKey, accessTags, embeddingModel);
		}
	}
}
