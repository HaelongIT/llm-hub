package com.llmhub.idx.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 블로킹 JPA 접근. 호출자는 격리 스케줄러에서 부른다 (S13, E12). */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

	Optional<DocumentEntity> findByDocKey(String docKey);

	/** 재색인 대상. 메타의 모델명으로 식별한다 (E9). */
	List<DocumentEntity> findByEmbeddingModelNot(String embeddingModel);
}
