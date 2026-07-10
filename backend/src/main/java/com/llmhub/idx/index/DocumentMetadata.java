package com.llmhub.idx.index;

import java.util.List;

/**
 * 상위 document의 색인용 정보 (PG의 {@code document} 레코드에서 온다).
 *
 * <p>{@code accessTags}는 <b>접근 태그의 유일한 원천</b>이다 (S18). 조각에 실리는 태그는 여기서 복사된 사본이다.
 *
 * @param documentId 시스템 생성 식별자
 * @param documentName 원본 파일명 (사용자에게 근거로 표시된다)
 * @param accessTags 문서 접근 태그. 사용자 태그와 <b>동일 어휘</b>다 (S3).
 */
public record DocumentMetadata(String documentId, String documentName, List<String> accessTags) {

	public DocumentMetadata {
		accessTags = List.copyOf(accessTags);
	}
}
