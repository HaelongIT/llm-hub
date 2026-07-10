package com.llmhub.idx.service;

import java.util.List;
import java.util.Optional;

/**
 * document 레코드 저장소 (PG).
 *
 * <p>블로킹 JPA 접근은 전용 리포지토리 계층에 격리된다 (S13, E12).
 */
public interface DocumentRepository {

	/**
	 * {@code docKey}가 이미 있으면 그 레코드를 갱신하고 <b>같은 id</b>를 돌려준다. 없으면 새로 만든다 (S17).
	 */
	DocumentRecord upsert(
			String docKey, String filename, String storageKey, List<String> accessTags, String embeddingModel);

	/** 재색인은 이 레코드의 원본 경로에서 다시 읽는다 (S16). */
	Optional<DocumentRecord> findByDocKey(String docKey);

	/** 현재 설정과 <b>다른</b> 임베딩 모델로 색인된 문서들. 재색인 대상이다 (E9). */
	List<DocumentRecord> findStale(String currentEmbeddingModel);
}
