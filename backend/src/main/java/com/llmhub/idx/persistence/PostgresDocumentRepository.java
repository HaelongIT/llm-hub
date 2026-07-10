package com.llmhub.idx.persistence;

import com.llmhub.idx.service.DocumentRecord;
import com.llmhub.idx.service.DocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DocumentRepository}의 PostgreSQL 구현.
 *
 * <p><b>블로킹이다.</b> 리액티브 흐름에서 부를 때는 반드시 격리 스케줄러로 넘긴다 (S13). 이 계층 안에 블로킹을 가둬두면
 * 나중에 R2DBC로 바꾸거나 스레드풀을 조정하는 일도 여기서 끝난다 (E12).
 */
@Repository
public class PostgresDocumentRepository implements DocumentRepository {

	// 청킹 버전은 상수가 아니다. ChunkingStrategy가 자기 버전을 알려주고 IndexingService가 넘긴다 (E11).

	private final DocumentJpaRepository jpaRepository;
	private final Clock clock;

	public PostgresDocumentRepository(DocumentJpaRepository jpaRepository, Clock clock) {
		this.jpaRepository = jpaRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public DocumentRecord upsert(
			String docKey,
			String filename,
			String storageKey,
			List<String> accessTags,
			String embeddingModel,
			String chunkingVersion,
			UUID uploadedBy) {
		Instant now = Instant.now(clock);
		String[] tags = accessTags.toArray(String[]::new);

		DocumentEntity entity =
				jpaRepository
						.findByDocKey(docKey)
						.map(
								existing -> {
									// 같은 doc_key는 같은 document다. id가 바뀌면 조각들이 고아가 된다 (S17).
									existing.replaceWith(filename, storageKey, tags, embeddingModel, chunkingVersion, uploadedBy, now);
									return existing;
								})
						.orElseGet(
								() ->
										new DocumentEntity(
												// 랜덤이 아니라 doc_key에서 결정적으로 유도한다. 색인 서비스가 커밋 전에
												// 같은 id로 ES 조각을 조립하기 때문이다 (R-3, DocumentId 참고).
												com.llmhub.idx.service.DocumentId.of(docKey),
												docKey,
												filename,
												storageKey,
												tags,
												embeddingModel,
												chunkingVersion,
												uploadedBy,
												now));

		DocumentEntity saved = jpaRepository.save(entity);
		return toRecord(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<DocumentRecord> findByDocKey(String docKey) {
		return jpaRepository.findByDocKey(docKey).map(PostgresDocumentRepository::toRecord);
	}

	@Override
	@Transactional(readOnly = true)
	public List<DocumentRecord> findStale(String currentEmbeddingModel) {
		return jpaRepository.findByEmbeddingModelNot(currentEmbeddingModel).stream()
				.map(PostgresDocumentRepository::toRecord)
				.toList();
	}

	private static DocumentRecord toRecord(DocumentEntity entity) {
		return new DocumentRecord(
				entity.getId().toString(),
				entity.getDocKey(),
				entity.getFilename(),
				entity.getOriginalPath(),
				Arrays.asList(entity.getAccessTags()),
				entity.getEmbeddingModel());
	}
}
