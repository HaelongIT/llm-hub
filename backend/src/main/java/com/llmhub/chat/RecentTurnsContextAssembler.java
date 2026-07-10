package com.llmhub.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * 최근 N턴만 담되, <b>토큰 예산</b>을 넘지 않는다 (S2, PERF-5).
 *
 * <p>한 턴은 질문과 답변 한 쌍이므로 메시지 {@code 2N}개다. 그러나 턴 수만으로는 길이가 잡히지 않는다 — 긴 메시지 하나면
 * 컨텍스트가 넘친다. 그래서 N턴으로 자른 뒤 <b>오래된 쪽부터</b> 버려 예산 안에 맞춘다.
 *
 * <p>메시지 하나가 예산보다 크면 컨텍스트는 비어 있다. "최근 것은 무조건 담는다"면 그것은 상한이 아니다. 지금 질문의 길이는 별도
 * 상한이 지킨다 ({@code llmhub.chat.max-question-chars}).
 *
 * <p>근거(sources)는 이 예산 밖이다. 그쪽은 {@code top-k} × 조각 크기로 이미 유계다.
 */
public final class RecentTurnsContextAssembler implements ContextAssembler {

	private static final int MESSAGES_PER_TURN = 2;

	private final int turns;
	private final int maxContextTokens;
	private final TokenCountEstimator tokenCounter;

	public RecentTurnsContextAssembler(int turns, int maxContextTokens) {
		this(turns, maxContextTokens, new JTokkitTokenCountEstimator());
	}

	RecentTurnsContextAssembler(int turns, int maxContextTokens, TokenCountEstimator tokenCounter) {
		if (turns <= 0) {
			throw new IllegalArgumentException("턴 수는 양수여야 한다: " + turns);
		}
		if (maxContextTokens <= 0) {
			throw new IllegalArgumentException("컨텍스트 토큰 상한은 양수여야 한다: " + maxContextTokens);
		}
		this.turns = turns;
		this.maxContextTokens = maxContextTokens;
		this.tokenCounter = tokenCounter;
	}

	@Override
	public List<Message> assemble(List<Message> history) {
		List<Message> recent = lastTurns(history);

		// 최근 것부터 채우고 예산을 넘기는 순간 멈춘다. 남은 것은 오래된 쪽이므로 버려진다.
		List<Message> kept = new ArrayList<>();
		int budget = maxContextTokens;
		for (int i = recent.size() - 1; i >= 0; i--) {
			int tokens = tokenCounter.estimate(recent.get(i).content());
			if (tokens > budget) {
				break;
			}
			budget -= tokens;
			kept.add(recent.get(i));
		}

		Collections.reverse(kept);
		return List.copyOf(kept);
	}

	private List<Message> lastTurns(List<Message> history) {
		int limit = turns * MESSAGES_PER_TURN;
		if (history.size() <= limit) {
			return history;
		}
		return history.subList(history.size() - limit, history.size());
	}
}
