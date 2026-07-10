package com.llmhub.idx.index;

import com.llmhub.common.embedding.EmbeddingSpec;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.OptionalInt;

/**
 * 조각을 Elasticsearch에 저장한다.
 *
 * <p>{@code chunk_text}는 nori로 분석된다 (S15). {@code embedding}은 cosine 유사도의 {@code
 * dense_vector}다. 두 필드가 한 문서에 함께 있어야 BM25와 벡터를 <b>단일 쿼리</b>로 결합할 수 있다 (S11,
 * PERF-3).
 */
public final class ElasticsearchChunkRepository implements ChunkRepository {

	/** 조회 상한. v0 색인 규모 전제. */
	private static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

	private final ElasticsearchClient client;
	private final String indexName;
	private final String analyzer;

	/**
	 * @param analyzer {@code chunk_text}의 BM25 분석기. 언어는 배포 설정이다 (S15, E16, REL-4). 바꾸면
	 *     <b>새 인덱스가 필요하다</b> — 매핑은 인덱스 생성 시 고정된다.
	 */
	public ElasticsearchChunkRepository(ElasticsearchClient client, String indexName, String analyzer) {
		this.client = client;
		this.indexName = indexName;
		this.analyzer = analyzer;
	}

	@Override
	public void createIndexIfMissing(EmbeddingSpec spec) {
		try {
			if (client.indices().exists(e -> e.index(indexName)).value()) {
				return;
			}
			client
					.indices()
					.create(
							c ->
									c.index(indexName)
											.mappings(
													m ->
															m.properties("chunk_text", p -> p.text(t -> t.analyzer(analyzer)))
																	.properties(
																			"embedding",
																			p ->
																					p.denseVector(
																							d ->
																									d.dims(spec.dimensions())
																											.index(true)
																											.similarity(DenseVectorSimilarity.Cosine)))
																	.properties("document_id", p -> p.keyword(k -> k))
																	.properties("document_name", p -> p.keyword(k -> k))
																	.properties("location", p -> p.keyword(k -> k))
																	.properties("access_tags", p -> p.keyword(k -> k))
																	.properties("indexed_at", p -> p.date(dt -> dt))
																	.properties("embedding_model", p -> p.keyword(k -> k))
																	.properties("embedding_dim", p -> p.integer(i -> i))
																	.properties("indexing_run_id", p -> p.keyword(k -> k))));
		} catch (IOException e) {
			throw new UncheckedIOException("인덱스 생성 실패: " + indexName, e);
		}
	}

	@Override
	public OptionalInt indexedDimensions() {
		try {
			if (!client.indices().exists(e -> e.index(indexName)).value()) {
				return OptionalInt.empty();
			}
			IndexMappingRecord mapping = client.indices().getMapping(g -> g.index(indexName)).get(indexName);
			if (mapping == null) {
				return OptionalInt.empty();
			}
			Property embedding = mapping.mappings().properties().get("embedding");
			if (embedding == null || !embedding.isDenseVector() || embedding.denseVector().dims() == null) {
				return OptionalInt.empty();
			}
			return OptionalInt.of(embedding.denseVector().dims());
		} catch (IOException e) {
			throw new UncheckedIOException("인덱스 매핑 조회 실패: " + indexName, e);
		}
	}

	@Override
	public void indexAll(List<EmbeddedChunk> chunks) {
		if (chunks.isEmpty()) {
			return;
		}
		BulkRequest.Builder bulk = new BulkRequest.Builder().index(indexName).refresh(Refresh.True);
		for (EmbeddedChunk embedded : chunks) {
			ChunkSource source = ChunkSource.from(embedded);
			bulk.operations(op -> op.index(i -> i.id(documentIdOf(embedded.chunk())).document(source)));
		}
		BulkResponse response;
		try {
			response = client.bulk(bulk.build());
		} catch (IOException e) {
			throw new UncheckedIOException("조각 색인 실패", e);
		}
		if (response.errors()) {
			// ES bulk는 원자적이지 않다. 성공한 op는 이미 반영됐으므로, 이번 run의 조각을 도로 지운 뒤
			// 실패로 끝낸다. 안 지우면 반쪽 색인이 구 run과 공존하며 중복 근거를 만든다 (R-11).
			rollback(chunks);
			throw new IllegalStateException("조각 색인 실패: " + firstError(response));
		}
	}

	/**
	 * 부분 실패한 bulk의 흔적을 지운다. 한 번의 {@code indexAll}은 한 문서·한 실행의 조각만 담으므로, 그
	 * {@code document_id}와 {@code indexing_run_id}로 이번 run의 조각을 정확히 겨냥한다.
	 */
	private void rollback(List<EmbeddedChunk> chunks) {
		IndexedChunk any = chunks.get(0).chunk();
		try {
			client.deleteByQuery(
					d ->
							d.index(indexName)
									.refresh(true)
									.query(
											q ->
													q.bool(
															b ->
																	b.must(m -> m.term(t -> t.field("document_id").value(any.documentId())))
																			.must(
																					m ->
																							m.term(
																									t ->
																											t.field("indexing_run_id")
																													.value(any.indexingRunId()))))));
		} catch (IOException e) {
			// 롤백까지 실패하면 반쪽 색인이 남는다. 삼키지 않고 이 더 급한 실패를 올린다.
			throw new UncheckedIOException("부분 색인 롤백 실패: " + any.documentId(), e);
		}
	}

	@Override
	public void deleteStaleChunks(String documentId, String currentIndexingRunId) {
		try {
			client.deleteByQuery(
					d ->
							d.index(indexName)
									.refresh(true)
									.query(
											q ->
													q.bool(
															b ->
																	b.must(m -> m.term(t -> t.field("document_id").value(documentId)))
																			.mustNot(
																					mn ->
																							mn.term(
																									t ->
																											t.field("indexing_run_id")
																													.value(currentIndexingRunId))))));
		} catch (IOException e) {
			throw new UncheckedIOException("구버전 조각 삭제 실패: " + documentId, e);
		}
	}

	@Override
	public List<IndexedChunk> findByDocumentId(String documentId) {
		try {
			return client
					.search(
							s ->
									s.index(indexName)
											.size(MAX_CHUNKS_PER_DOCUMENT)
											.query(q -> q.term(t -> t.field("document_id").value(documentId))),
							ChunkSource.class)
					.hits()
					.hits()
					.stream()
					.map(hit -> hit.source().toIndexedChunk())
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("조각 조회 실패: " + documentId, e);
		}
	}

	/**
	 * 결정적 문서 ID. 같은 실행에서 같은 조각을 두 번 색인해도 중복이 생기지 않는다. 실행 ID가 들어가므로 신·구 버전 조각이
	 * 서로를 덮어쓰지 않는다 (S17).
	 */
	private static String documentIdOf(IndexedChunk chunk) {
		return "%s:%s:%s".formatted(chunk.documentId(), chunk.indexingRunId(), chunk.location());
	}

	private static String firstError(BulkResponse response) {
		return response.items().stream()
				.filter(item -> item.error() != null)
				.findFirst()
				.map(item -> item.error().reason())
				.orElse("(원인 미상)");
	}
}
