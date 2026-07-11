package com.llmhub.idx.service;

/**
 * 현재 설정과 다른 임베딩 모델 <b>또는</b> 청킹 버전으로 색인된 문서. 재색인 대상이다 (E9, E11).
 *
 * <p>저장 키(원본 경로)를 담지 않는다. 내부 식별자를 API로 흘리지 않는다.
 *
 * @param reason 왜 대상인지 — 모델·청킹·둘 다. 운영자가 무엇 때문인지 알 수 있게 한다.
 */
public record StaleDocument(
		String docKey,
		String filename,
		String embeddingModel,
		String chunkingVersion,
		StaleReason reason) {}
