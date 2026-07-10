package com.llmhub.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

/**
 * 단일 ES 쿼리로 BM25(nori)와 벡터를 결합해 검색한다 (S11, PERF-3).
 *
 * <p>쿼리 조립은 {@link ResultMerger}가 한다. 병합 전략을 바꿔도 이 클래스와 색인 구조는 그대로다 (E10).
 *
 * <p>ES 장애는 깨끗한 실패다. 자동 우회·페일오버가 없다 (S8-3).
 */
public final class ElasticsearchChunkSearchRepository implements ChunkSearchRepository {

	private final ElasticsearchClient client;
	private final String indexName;
	private final ResultMerger merger;

	public ElasticsearchChunkSearchRepository(
			ElasticsearchClient client, String indexName, ResultMerger merger) {
		this.client = client;
		this.indexName = indexName;
		this.merger = merger;
	}

	@Override
	public List<Source> search(String queryText, float[] queryVector, Set<String> accessTags, int topK) {
		if (accessTags.isEmpty()) {
			// 빈 태그로는 어떤 조각과도 교집합이 없다. 게이트웨이를 부를 이유도 없다.
			return List.of();
		}
		try {
			return client
					.search(merger.build(indexName, queryText, queryVector, accessTags, topK), Hit.class)
					.hits()
					.hits()
					.stream()
					.map(
							hit ->
									new Source(
											hit.source().documentId(),
											hit.source().documentName(),
											hit.source().location(),
											hit.source().chunkText(),
											hit.score() == null ? 0.0 : hit.score()))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("검색 실패: " + queryText, e);
		}
	}

	/**
	 * 검색에 필요한 필드만 읽는다. IDX의 조각 DTO를 재사용하지 않는다 — SEARCH는 IDX에 의존하지 않는다(docs/01).
	 * 두 모듈은 코드가 아니라 ES 인덱스 스키마로만 만난다.
	 *
	 * <p>{@code embedding}·{@code access_tags} 같은 나머지 필드는 읽지 않는다. 신규 메타 필드가 추가되어도
	 * 검색이 깨지지 않아야 한다 (E6).
	 */
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	record Hit(
			@JsonProperty("chunk_text") String chunkText,
			@JsonProperty("document_id") String documentId,
			@JsonProperty("document_name") String documentName,
			@JsonProperty("location") String location) {}
}
