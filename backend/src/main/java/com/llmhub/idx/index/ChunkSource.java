package com.llmhub.idx.index;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * ES 문서의 {@code _source} 모양. 필드명은 {@code docs/03-data-model.md}의 조각 인덱스와 정확히 일치한다.
 *
 * <p>{@code indexedAt}을 ISO-8601 문자열로 다룬다. Jackson의 시간 모듈 등록 여부에 결과가 좌우되지 않게 하기
 * 위해서다.
 */
record ChunkSource(
		@JsonProperty("chunk_text") String chunkText,
		@JsonProperty("embedding") float[] embedding,
		@JsonProperty("document_id") String documentId,
		@JsonProperty("document_name") String documentName,
		@JsonProperty("location") String location,
		@JsonProperty("access_tags") List<String> accessTags,
		@JsonProperty("indexed_at") String indexedAt,
		@JsonProperty("embedding_model") String embeddingModel,
		@JsonProperty("embedding_dim") int embeddingDim,
		@JsonProperty("indexing_run_id") String indexingRunId) {

	static ChunkSource from(EmbeddedChunk embedded) {
		IndexedChunk chunk = embedded.chunk();
		return new ChunkSource(
				chunk.text(),
				embedded.embedding(),
				chunk.documentId(),
				chunk.documentName(),
				chunk.location(),
				chunk.accessTags(),
				chunk.indexedAt().toString(),
				chunk.embeddingModel(),
				chunk.embeddingDim(),
				chunk.indexingRunId());
	}

	IndexedChunk toIndexedChunk() {
		return new IndexedChunk(
				documentId,
				documentName,
				location,
				accessTags,
				Instant.parse(indexedAt),
				embeddingModel,
				embeddingDim,
				indexingRunId,
				chunkText);
	}
}
