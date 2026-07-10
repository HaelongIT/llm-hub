package com.llmhub.idx.service;

/**
 * 현재 설정과 다른 임베딩 모델로 색인된 문서. 재색인 대상이다 (E9).
 *
 * <p>저장 키(원본 경로)를 담지 않는다. 내부 식별자를 API로 흘리지 않는다.
 */
public record StaleDocument(String docKey, String filename, String embeddingModel) {}
