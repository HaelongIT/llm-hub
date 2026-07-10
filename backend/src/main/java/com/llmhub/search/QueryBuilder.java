package com.llmhub.search;

/**
 * 질문 → 검색 쿼리. <b>독립 단계</b>다 (E3).
 *
 * <p>v0는 마지막 사용자 질문을 그대로 쓴다. 향후 쿼리 재작성(다중 질의 확장, 대화 맥락 반영)을 이 앞에 끼워 넣을 수 있도록
 * 단계로 분리해 둔다.
 */
public interface QueryBuilder {

	String build(String lastUserQuestion);
}
