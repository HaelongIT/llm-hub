package com.llmhub.idx.service;

import java.util.List;

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
}
