package com.llmhub.search;

import java.util.List;
import java.util.Set;

/**
 * 접근 태그로 필터된 하이브리드 검색.
 *
 * <p>이 계층은 <b>권한을 판단하지 않는다.</b> AUTH가 앞단 게이트에서 확정한 태그를 소비만 한다 (S4).
 */
public interface ChunkSearchRepository {

	/**
	 * @param accessTags 사용자 태그. 조각의 {@code access_tags}와 교집합이 있는 것만 결과에 들어온다.
	 * @return 점수 내림차순 근거 목록. 권한 없는 조각은 애초에 여기에 없다 (SEC-2).
	 */
	List<Source> search(String queryText, float[] queryVector, Set<String> accessTags, int topK);
}
