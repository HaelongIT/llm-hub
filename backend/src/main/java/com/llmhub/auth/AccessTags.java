package com.llmhub.auth;

import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 앞단 게이트가 확정한 접근 태그를 하위 계층에 전달한다 (S4).
 *
 * <p>Reactor {@code Context}에 실어 나른다. Context는 구독에 묶여 있어 스레드와 무관하게 스트리밍 응답의 모든
 * 연산자에서 보인다. {@code ServerWebExchange}를 하위 계층까지 끌고 다닐 필요가 없다.
 *
 * <p>하위 계층은 <b>읽기만</b> 한다. 태그를 다시 계산하거나 덧붙이지 않는다.
 */
public final class AccessTags {

	private static final String KEY = AccessTags.class.getName();

	private AccessTags() {}

	/** 게이트가 태그를 싣는다. */
	static Context with(Context context, Set<String> tags) {
		return context.put(KEY, Set.copyOf(tags));
	}

	/**
	 * 하위 계층이 태그를 읽는다. 게이트를 통과하지 않았으면 빈 집합이다 — 빈 태그로는 어떤 조각과도 교집합이 생기지 않으므로
	 * 검색 결과가 비어 있게 된다.
	 */
	public static Mono<Set<String>> current() {
		return Mono.deferContextual(ctx -> Mono.just(ctx.getOrDefault(KEY, Set.of())));
	}
}
