package com.llmhub.idx.index;

import com.llmhub.idx.chunking.Chunk;
import java.time.Instant;
import java.util.List;

/**
 * 조각에 색인 메타데이터를 입혀 ES에 저장할 형태로 만든다.
 *
 * <p>접근 태그는 document에서 <b>복사</b>한다. 조각은 색인 시점의 권한 스냅샷을 들고 있고, 진실은 언제나 document에
 * 있다 (S18).
 */
public final class ChunkAssembler {

	/**
	 * @param indexingRunId 이번 색인 실행의 식별자. 교체 시 구버전 조각을 이 값으로 골라 지운다 (S17).
	 * @throws IllegalArgumentException document에 접근 태그가 없는 경우
	 */
	public List<IndexedChunk> assemble(
			DocumentMetadata document,
			List<Chunk> chunks,
			EmbeddingSpec embedding,
			String indexingRunId,
			Instant indexedAt) {

		if (document.accessTags().isEmpty()) {
			throw new IllegalArgumentException(
					"접근 태그가 없는 문서는 색인할 수 없다. 어떤 사용자에게도 검색되지 않는다: " + document.documentId());
		}

		return chunks.stream()
				.map(
						chunk ->
								new IndexedChunk(
										document.documentId(),
										document.documentName(),
										chunk.location(),
										document.accessTags(),
										indexedAt,
										embedding.model(),
										embedding.dimensions(),
										indexingRunId,
										chunk.text()))
				.toList();
	}
}
