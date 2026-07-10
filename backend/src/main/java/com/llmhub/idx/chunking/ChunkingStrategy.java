package com.llmhub.idx.chunking;

import java.util.List;

/**
 * 텍스트를 조각으로 나눈다.
 *
 * <p>교체 가능한 부품이다 (E11). 교체하면 재색인이 필요하다. 이 계약에는 구현 라이브러리의 타입이 등장하지 않는다 —
 * 등장하는 순간 교체가 불가능해진다.
 *
 * <p>v0는 토큰 크기 기준 기계적 청킹이다. "조항 단위" 같은 도메인 청킹을 하지 않는다 (S12).
 */
public interface ChunkingStrategy {

	/**
	 * @param text 추출된 문서 전문
	 * @return 순서가 보존된 조각들. 빈 텍스트면 빈 목록.
	 */
	List<Chunk> chunk(String text);

	/**
	 * 이 전략의 식별자. {@code document.chunking_version}에 기록된다 (docs/03).
	 *
	 * <p>전략을 바꾸면 이 값도 바꾼다. 그래야 어떤 문서가 옛 전략으로 색인되었는지 알 수 있다 (E11). 임베딩의 {@code
	 * EmbeddingSpec.model()}과 같은 역할이다.
	 */
	String version();
}
