package com.llmhub.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.List;
import java.util.Set;

/**
 * 최상위 {@code knn} 절과 {@code query} 절을 한 요청에 넣는다. ES가 두 점수를 <b>합산</b>하고, 각 절의
 * {@code boost}가 가중치가 된다. 무료 티어에서 확실히 동작한다 (S11, PERF-3).
 *
 * <p><b>접근 태그 필터를 양쪽에 건다.</b> {@code knn} 절의 {@code filter}는 후보군 진입 전에 걸리는
 * pre-filter다. 반면 Query DSL 트리 다른 곳의 필터는 kNN에 대해 post-filter로 적용되어, 권한 없는 조각이 후보
 * 풀에 들어왔다가 나중에 제거된다. 그러면 결과가 {@code k}보다 적어질 수 있고, SEC-2가 요구하는 "검색 단계에서 배제"를
 * 문자 그대로 어긴다.
 *
 * <p>BM25 점수와 벡터 점수는 스케일이 다르다. boost는 배포마다 튜닝한다 (REL-4).
 */
public final class LinearCombinationMerger implements ResultMerger {

	/** kNN 후보군 크기. top-k보다 넉넉해야 재현율이 유지된다. */
	private static final int CANDIDATE_MULTIPLIER = 10;

	private final float bm25Boost;
	private final float vectorBoost;

	public LinearCombinationMerger(float bm25Boost, float vectorBoost) {
		this.bm25Boost = bm25Boost;
		this.vectorBoost = vectorBoost;
	}

	@Override
	public SearchRequest build(
			String indexName, String queryText, float[] queryVector, Set<String> accessTags, int topK) {

		Query 태그_필터 = accessTagsFilter(accessTags);

		return SearchRequest.of(
				s ->
						s.index(indexName)
								.size(topK)
								// 벡터: pre-filter로 권한 없는 조각을 후보군에서 아예 배제한다.
								.knn(
										knn ->
												knn.field("embedding")
														.queryVector(toFloatList(queryVector))
														.k(topK)
														.numCandidates(topK * CANDIDATE_MULTIPLIER)
														.filter(태그_필터)
														.boost(vectorBoost))
								// BM25(nori): 같은 필터를 filter 절에 건다.
								.query(
										q ->
												q.bool(
														b ->
																b.must(
																				m ->
																						m.match(
																								match ->
																										match
																												.field("chunk_text")
																												.query(queryText)
																												.boost(bm25Boost)))
																		.filter(태그_필터))));
	}

	private static Query accessTagsFilter(Set<String> accessTags) {
		List<co.elastic.clients.elasticsearch._types.FieldValue> values =
				accessTags.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList();
		return Query.of(
				q -> q.terms(t -> t.field("access_tags").terms(TermsQueryField.of(f -> f.value(values)))));
	}

	private static List<Float> toFloatList(float[] vector) {
		List<Float> list = new java.util.ArrayList<>(vector.length);
		for (float v : vector) {
			list.add(v);
		}
		return List.copyOf(list);
	}
}
