package com.llmhub.idx.service;

import java.util.List;

/**
 * PG의 {@code document} 레코드 (docs/03).
 *
 * @param storageKey 보관된 원본 참조. 재색인은 이 원본에서 수행한다 (S16).
 * @param accessTags 접근 태그의 유일한 원천 (S18).
 */
public record DocumentRecord(
		String id,
		String docKey,
		String filename,
		String storageKey,
		List<String> accessTags,
		String embeddingModel) {

	public DocumentRecord {
		accessTags = List.copyOf(accessTags);
	}
}
