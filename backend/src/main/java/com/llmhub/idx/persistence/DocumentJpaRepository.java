package com.llmhub.idx.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 블로킹 JPA 접근. 호출자는 격리 스케줄러에서 부른다 (S13, E12). */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

	Optional<DocumentEntity> findByDocKey(String docKey);

	/**
	 * 재색인 대상. 임베딩 모델이 다르거나 청킹 버전이 다른 문서 (E9, E11). 두 컬럼 모두 not-null이라 OR 비교가
	 * 명확하다.
	 */
	List<DocumentEntity> findByEmbeddingModelNotOrChunkingVersionNot(
			String embeddingModel, String chunkingVersion);
}
