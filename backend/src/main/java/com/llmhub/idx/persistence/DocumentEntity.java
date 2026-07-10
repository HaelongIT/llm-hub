package com.llmhub.idx.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** docs/03의 {@code document} 테이블. 접근 태그의 유일한 원천이다 (S18). */
@Entity
@Table(name = "document")
public class DocumentEntity {

	@Id private UUID id;

	@Column(name = "doc_key", nullable = false, unique = true)
	private String docKey;

	@Column(nullable = false)
	private String filename;

	/** 보관된 원본 참조 (S16). 저장 백엔드가 바뀌어도 이 값은 불투명한 키다 (E14). */
	@Column(name = "original_path", nullable = false)
	private String originalPath;

	/** PostgreSQL {@code text[]}. Hibernate 6이 {@code String[]}을 배열 타입으로 매핑한다. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "access_tags", nullable = false)
	private String[] accessTags;

	@Column(name = "embedding_model", nullable = false)
	private String embeddingModel;

	@Column(name = "chunking_version", nullable = false)
	private String chunkingVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected DocumentEntity() {}

	DocumentEntity(
			UUID id,
			String docKey,
			String filename,
			String originalPath,
			String[] accessTags,
			String embeddingModel,
			String chunkingVersion,
			Instant now) {
		this.id = id;
		this.docKey = docKey;
		this.filename = filename;
		this.originalPath = originalPath;
		this.accessTags = accessTags.clone();
		this.embeddingModel = embeddingModel;
		this.chunkingVersion = chunkingVersion;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** 재업로드. id와 생성 시각은 유지한다 (S17). */
	void replaceWith(String filename, String originalPath, String[] accessTags, String embeddingModel, Instant now) {
		this.filename = filename;
		this.originalPath = originalPath;
		this.accessTags = accessTags.clone();
		this.embeddingModel = embeddingModel;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getDocKey() {
		return docKey;
	}

	public String getFilename() {
		return filename;
	}

	public String getOriginalPath() {
		return originalPath;
	}

	public String[] getAccessTags() {
		return accessTags.clone();
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
