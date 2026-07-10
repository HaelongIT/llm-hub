package com.llmhub.search;

/**
 * 응답에 실리는 근거 한 건.
 *
 * <p><b>서버가 실제로 검색한 조각에서 생성된다.</b> LLM 출력에서 근거를 파싱하지 않는다 (S6). 프롬프트 인젝션이 근거를
 * 위조할 수 없는 이유가 이것이다.
 *
 * @param score 하이브리드 점수. BM25와 벡터 점수의 합이다.
 */
public record Source(String documentId, String documentName, String location, String text, double score) {}
