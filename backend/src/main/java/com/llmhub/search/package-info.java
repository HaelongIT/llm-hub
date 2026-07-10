/**
 * SEARCH — 접근 태그로 필터된 하이브리드 검색(BM25 + 벡터)과 sources 생성.
 *
 * <p>권한을 판단하지 않는다. AUTH가 확정한 태그를 소비만 한다(S4).
 * sources는 실제 검색된 조각에서만 나온다. LLM 출력에서 근거를 만들지 않는다(S6).
 *
 * @see <a href="../../../../../../docs/requirements/REQ-SEARCH-retrieval.md">REQ-SEARCH</a>
 */
package com.llmhub.search;
