package com.llmhub.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-SEARCH: 접근 태그 필터는 <b>검색 단계에서</b> 배제한다. 응답에서 제거하는 것이 아니다 (SEC-2).
 *
 * <p>결정적인 세부: {@code knn} 절은 자체 {@code filter}를 갖고 이것은 후보군 진입 <b>전에</b> 걸리는
 * pre-filter다. 반면 Query DSL 트리 다른 곳의 필터는 kNN에 대해 <b>post-filter</b>로 적용된다. 후자만
 * 쓰면 권한 없는 조각이 후보 풀에 들어왔다가 나중에 제거되고, 결과가 {@code k}보다 적어질 수 있다.
 *
 * <p>그래서 같은 {@code terms} 필터를 {@code knn.filter}와 {@code bool.filter} <b>양쪽에</b> 건다.
 * 이 테스트는 컨테이너 없이 조립된 쿼리를 직접 들여다본다.
 *
 * <p>E10: 병합 전략은 교체 가능하다. 병합은 ES 안에서 일어나므로, 교체 가능한 이음매는 "쿼리를 어떻게 조립하는가"다. RRF로
 * 바꿔도 색인 구조는 그대로다.
 */
class LinearCombinationMergerTest {

	private static final float[] 질문벡터 = {0.1f, 0.2f, 0.3f};
	private static final Set<String> 사용자_태그 = Set.of("public");

	private final ResultMerger merger = new LinearCombinationMerger(1.0f, 2.0f);

	private String 조립된_쿼리() {
		SearchRequest request = merger.build("llmhub-chunks", "연차휴가", 질문벡터, 사용자_태그, 5);
		return request.toString();
	}

	@Test
	@DisplayName("BM25 절과 kNN 절이 한 요청에 함께 담긴다")
	void 단일_쿼리로_하이브리드를_구성한다() {
		String query = 조립된_쿼리();

		assertThat(query).as("앱 레벨에서 두 번 호출하지 않는다 (PERF-3)").contains("knn").contains("chunk_text");
	}

	@Test
	@DisplayName("접근 태그 필터가 kNN 절 안에 pre-filter로 걸린다")
	void kNN에_pre_filter가_걸린다() {
		SearchRequest request = merger.build("llmhub-chunks", "연차휴가", 질문벡터, 사용자_태그, 5);

		assertThat(request.knn()).hasSize(1);
		assertThat(request.knn().get(0).filter())
				.as("kNN 자체 필터가 없으면 권한 없는 조각이 후보 풀에 들어왔다가 제거된다 (SEC-2)")
				.isNotEmpty();
		assertThat(request.knn().get(0).filter().toString()).contains("access_tags").contains("public");
	}

	@Test
	@DisplayName("접근 태그 필터가 BM25 절에도 걸린다")
	void BM25에도_필터가_걸린다() {
		String query = 조립된_쿼리();

		assertThat(query.split("access_tags", -1).length - 1)
				.as("kNN과 BM25 양쪽에 같은 필터를 걸어야 어느 쪽으로도 새지 않는다")
				.isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("가중치가 두 절의 boost로 표현된다")
	void 가중치가_boost로_표현된다() {
		SearchRequest request = merger.build("llmhub-chunks", "연차휴가", 질문벡터, 사용자_태그, 5);

		assertThat(request.knn().get(0).boost()).isEqualTo(2.0f);
		assertThat(request.toString()).contains("\"boost\":1.0");
	}

	@Test
	@DisplayName("top-k가 설정값으로 전달된다")
	void topK가_전달된다() {
		SearchRequest request = merger.build("llmhub-chunks", "연차휴가", 질문벡터, 사용자_태그, 7);

		assertThat(request.size()).isEqualTo(7);
		assertThat(request.knn().get(0).k()).isEqualTo(7);
	}

	@Test
	@DisplayName("병합 전략을 바꿔도 색인 구조는 그대로다 (E10)")
	void 병합_전략_교체가_색인을_바꾸지_않는다() {
		ResultMerger 벡터_우선 = new LinearCombinationMerger(0.1f, 5.0f);

		SearchRequest request = 벡터_우선.build("llmhub-chunks", "연차휴가", 질문벡터, 사용자_태그, 5);

		assertThat(request.knn().get(0).boost()).isEqualTo(5.0f);
		assertThat(request.index()).containsExactly("llmhub-chunks");
		assertThat(request.toString())
				.as("같은 필드(chunk_text, embedding, access_tags)를 읽는다. 재색인이 필요 없다")
				.contains("chunk_text")
				.contains("embedding")
				.contains("access_tags");
	}

	@Test
	@DisplayName("태그가 비면 아무 조각과도 교집합이 없어 결과가 비어야 한다")
	void 빈_태그는_아무것도_통과시키지_않는다() {
		SearchRequest request = merger.build("llmhub-chunks", "연차휴가", 질문벡터, Set.of(), 5);

		assertThat(request.toString())
				.as("빈 terms 필터는 어떤 문서와도 매치되지 않는다")
				.contains("access_tags");
	}
}
