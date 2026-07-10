package com.llmhub.search;

/** v0: 마지막 질문 그대로. 쿼리 재작성은 v0 스코프 밖이다 (S2, E3). */
public final class PassthroughQueryBuilder implements QueryBuilder {

	@Override
	public String build(String lastUserQuestion) {
		return lastUserQuestion;
	}
}
