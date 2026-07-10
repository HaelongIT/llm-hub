package com.llmhub.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.Set;

/**
 * BM25와 벡터 검색 결과의 병합 전략 (S11, E10).
 *
 * <p>병합은 Elasticsearch 안에서 일어난다. 앱 레벨에서 두 번 호출해 합치지 않는다 (PERF-3). 따라서 교체 가능한
 * 이음매는 "쿼리를 어떻게 조립하는가"다. 전략을 바꿔도 같은 필드를 읽으므로 <b>색인 구조는 그대로이고 재색인이 필요 없다</b>.
 *
 * <p>RRF는 쓰지 않는다. Elastic의 현재 구독 표는 무료 티어로 표시하지만 과거에는 유료 라이선스를 요구했고 전환 버전이
 * 확인되지 않는다. 회사별 설치형이라 고객의 ES 버전을 통제할 수 없다 (docs/LEARNINGS).
 */
public interface ResultMerger {

	/**
	 * @param accessTags AUTH가 확정한 사용자 태그. 이 계층은 소비만 하며 권한을 판단하지 않는다 (S4).
	 */
	SearchRequest build(
			String indexName, String queryText, float[] queryVector, Set<String> accessTags, int topK);
}
