package com.llmhub.idx.index;

import java.util.List;

/**
 * 조각 저장소 (ES).
 *
 * <p>Spring AI의 {@code VectorStore}를 쓰지 않는다. 그 추상화의 {@code similaritySearch()}는 kNN
 * 전용이라 BM25와의 하이브리드 검색이 불가능하고(S11, PERF-3), 조각의 메타 7종과 {@code access_tags}를 우리가
 * 원하는 모양으로 통제할 수 없다.
 */
public interface ChunkRepository {

	/**
	 * 인덱스가 없으면 만든다. {@code dense_vector}의 차원은 인덱스 생성 시 고정되므로, 임베딩 차원을 바꾸려면 새 인덱스가
	 * 필요하다 (docs/03).
	 */
	void createIndexIfMissing(EmbeddingSpec spec);

	/** 조각들을 한 번의 bulk 요청으로 색인한다. */
	void indexAll(List<EmbeddedChunk> chunks);

	/** 해당 문서의 조각을 모두 가져온다. */
	List<IndexedChunk> findByDocumentId(String documentId);
}
