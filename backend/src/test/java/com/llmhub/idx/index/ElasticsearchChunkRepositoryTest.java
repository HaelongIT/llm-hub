package com.llmhub.idx.index;

import com.llmhub.common.embedding.EmbeddingSpec;
import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import com.llmhub.idx.chunking.Chunk;
import com.llmhub.support.ElasticsearchTestSupport;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: txt 문서를 색인하면 조각이 ES에 저장되고 메타데이터 7종이 모두 존재한다.
 *
 * <p>실제 엔진 동작(nori 형태소, 매핑)에 의존하므로 Testcontainers 통합 테스트다 (docs/05). compose와 <b>같은
 * Dockerfile</b>로 이미지를 빌드한다 — 그래야 색인과 검색이 같은 분석기를 쓴다는 보장이 생긴다.
 */
class ElasticsearchChunkRepositoryTest {

	private static final String 인덱스 = "llmhub-chunks-test";
	private static final EmbeddingSpec 임베딩 = new EmbeddingSpec("stub-embedding", 4);
	private static final Instant 색인시각 = Instant.parse("2026-07-10T02:00:00Z");

	private static ElasticsearchClient client;
	private static ElasticsearchChunkRepository repository;

	@BeforeAll
	static void 인덱스를_만든다() {
		client = ElasticsearchTestSupport.client();
		repository = new ElasticsearchChunkRepository(client, 인덱스);
		repository.createIndexIfMissing(임베딩);
	}

	@Test
	@DisplayName("색인된 조각은 메타데이터 7종을 모두 갖는다")
	void 색인된_조각이_메타_7종을_갖는다() {
		DocumentMetadata 문서 = new DocumentMetadata("doc-meta", "인사규정.txt", List.of("public"));
		색인한다(문서, "run-a", new Chunk("연차휴가는 15일이다.", "0"), new Chunk("휴가는 1년간 행사한다.", "1"));

		List<IndexedChunk> 조회 = repository.findByDocumentId("doc-meta");

		assertThat(조회).hasSize(2);
		assertThat(조회).allSatisfy(c -> {
			assertThat(c.documentId()).isEqualTo("doc-meta");
			assertThat(c.documentName()).isEqualTo("인사규정.txt");
			assertThat(c.location()).isNotBlank();
			assertThat(c.accessTags()).containsExactly("public");
			assertThat(c.indexedAt()).isEqualTo(색인시각);
			assertThat(c.embeddingModel()).isEqualTo("stub-embedding");
			assertThat(c.embeddingDim()).isEqualTo(4);
		});
		assertThat(조회).extracting(IndexedChunk::text).contains("연차휴가는 15일이다.");
	}

	@Test
	@DisplayName("조각의 access_tags는 document의 access_tags 사본이다")
	void 태그가_문서에서_복사된다() {
		DocumentMetadata 문서 = new DocumentMetadata("doc-tags", "기밀.txt", List.of("restricted", "public"));
		색인한다(문서, "run-b", new Chunk("기밀 내용", "0"));

		assertThat(repository.findByDocumentId("doc-tags"))
				.singleElement()
				.satisfies(c -> assertThat(c.accessTags()).containsExactlyInAnyOrder("restricted", "public"));
	}

	@Test
	@DisplayName("chunk_text가 nori 형태소 분석기로 분석된다")
	void nori_분석기가_적용된다() throws IOException {
		var 결과 =
				client
						.indices()
						.analyze(AnalyzeRequest.of(a -> a.index(인덱스).field("chunk_text").text("연차휴가는")));

		List<String> 토큰 = 결과.tokens().stream().map(t -> t.token()).toList();
		assertThat(토큰)
				.as("nori가 없으면 '연차휴가는'이 통째로 한 토큰이 되어 '연차휴가'로 검색되지 않는다 (S15)")
				.contains("연차", "휴가");
	}

	@Test
	@DisplayName("구버전 조각만 지우고 신버전은 남긴다")
	void 구버전_조각만_지운다() {
		DocumentMetadata 문서 = new DocumentMetadata("doc-replace", "규정.txt", List.of("public"));
		색인한다(문서, "run-old", new Chunk("구버전 내용", "0"), new Chunk("구버전 둘째", "1"));
		색인한다(문서, "run-new", new Chunk("신버전 내용", "0"));

		assertThat(repository.findByDocumentId("doc-replace"))
				.as("삭제 전에는 신·구가 공존한다. 그래서 삭제 순서가 중요하다")
				.hasSize(3);

		repository.deleteStaleChunks("doc-replace", "run-new");

		assertThat(repository.findByDocumentId("doc-replace"))
				.singleElement()
				.satisfies(c -> {
					assertThat(c.indexingRunId()).isEqualTo("run-new");
					assertThat(c.text()).isEqualTo("신버전 내용");
				});
	}

	@Test
	@DisplayName("다른 문서의 조각은 건드리지 않는다")
	void 다른_문서는_건드리지_않는다() {
		색인한다(new DocumentMetadata("doc-x", "x.txt", List.of("public")), "run-x", new Chunk("x 내용", "0"));
		색인한다(new DocumentMetadata("doc-y", "y.txt", List.of("public")), "run-y", new Chunk("y 내용", "0"));

		repository.deleteStaleChunks("doc-x", "run-없는실행");

		assertThat(repository.findByDocumentId("doc-x")).isEmpty();
		assertThat(repository.findByDocumentId("doc-y")).hasSize(1);
	}

	private static void 색인한다(DocumentMetadata 문서, String 실행ID, Chunk... 조각들) {
		List<IndexedChunk> indexed =
				new ChunkAssembler().assemble(문서, List.of(조각들), 임베딩, 실행ID, 색인시각);
		List<EmbeddedChunk> embedded =
				indexed.stream().map(c -> new EmbeddedChunk(c, new float[] {0.1f, 0.2f, 0.3f, 0.4f})).toList();
		repository.indexAll(embedded);
	}
}
