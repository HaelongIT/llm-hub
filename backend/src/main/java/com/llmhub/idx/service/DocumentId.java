package com.llmhub.idx.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@code doc_key}에서 결정적으로 유도한 document id.
 *
 * <p>같은 {@code doc_key}는 같은 document다 (S17) — 그러므로 id도 {@code doc_key}로부터 나온다. 이렇게 하면
 * 색인 서비스가 <b>PG에 쓰기 전에</b> id를 알 수 있다. ES 조각을 먼저 색인하고 성공을 확인한 뒤에야 PG 메타데이터를
 * 커밋하려면(R-3, 유령 문서 방지), id가 커밋보다 먼저 필요하다.
 */
public final class DocumentId {

	private DocumentId() {}

	public static UUID of(String docKey) {
		return UUID.nameUUIDFromBytes(("llmhub-document:" + docKey).getBytes(StandardCharsets.UTF_8));
	}
}
