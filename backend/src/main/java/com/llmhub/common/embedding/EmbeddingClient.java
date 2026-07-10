package com.llmhub.common.embedding;

import java.util.List;

/**
 * 텍스트를 벡터로 만든다. LiteLLM 게이트웨이를 경유하며 모델은 설정값으로 고정된다 (S8-4, E9).
 *
 * <p>색인과 검색이 <b>같은 모델</b>을 써야 한다. 다르면 에러 없이 검색 품질만 붕괴한다. 그래서 어떤 모델을 썼는지
 * {@link #spec()}으로 드러내고 조각 메타데이터에 기록한다.
 *
 * <p>임베딩을 바꾸면 재색인이 필요하다. 재색인 대상은 조각 메타의 모델명·차원으로 식별한다 (E9).
 */
public interface EmbeddingClient {

	/** 이 클라이언트가 쓰는 모델과 차원. 설정값이다. */
	EmbeddingSpec spec();

	/** 입력 순서와 같은 순서로 벡터를 돌려준다. */
	List<float[]> embed(List<String> texts);
}
