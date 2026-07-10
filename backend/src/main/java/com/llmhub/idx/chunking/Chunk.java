package com.llmhub.idx.chunking;

/**
 * 문서에서 잘라낸 조각 하나.
 *
 * @param text 조각 원문. ES에 {@code chunk_text}로 저장되어 BM25(nori) 검색 대상이 된다 (S11, S15).
 * @param location 페이지·섹션 또는 순번. 필수 메타데이터 7종 중 하나다 (S7).
 */
public record Chunk(String text, String location) {}
