package com.llmhub.idx.index;

import java.time.Instant;
import java.util.List;

/**
 * ES에 저장될 조각 하나. 필수 메타데이터 7종을 전부 갖는다 (S7).
 *
 * <p>7종: {@code documentId}, {@code documentName}, {@code location}, {@code accessTags},
 * {@code indexedAt}, {@code embeddingModel}, {@code embeddingDim}.
 *
 * @param accessTags document에서 복사된 <b>사본</b>. 원천은 document다 (S18).
 * @param indexingRunId 이 조각을 만든 색인 실행. 교체 시 구버전 조각 삭제에 쓴다 (S17).
 * @param text {@code chunk_text}로 저장되어 BM25(nori) 검색 대상이 된다 (S11, S15).
 */
public record IndexedChunk(
		String documentId,
		String documentName,
		String location,
		List<String> accessTags,
		Instant indexedAt,
		String embeddingModel,
		int embeddingDim,
		String indexingRunId,
		String text) {

	public IndexedChunk {
		accessTags = List.copyOf(accessTags);
	}
}
