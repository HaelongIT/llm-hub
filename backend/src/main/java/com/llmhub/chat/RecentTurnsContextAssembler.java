package com.llmhub.chat;

import java.util.List;

/**
 * 최근 N턴만 담는다 (S2). 한 턴은 질문과 답변 한 쌍이므로 메시지 {@code 2N}개다.
 *
 * <p>무한 누적을 막아 LLM 컨텍스트 길이 초과를 방지한다 (PERF-5).
 */
public final class RecentTurnsContextAssembler implements ContextAssembler {

	private static final int MESSAGES_PER_TURN = 2;

	private final int turns;

	public RecentTurnsContextAssembler(int turns) {
		if (turns <= 0) {
			throw new IllegalArgumentException("턴 수는 양수여야 한다: " + turns);
		}
		this.turns = turns;
	}

	@Override
	public List<Message> assemble(List<Message> history) {
		int limit = turns * MESSAGES_PER_TURN;
		if (history.size() <= limit) {
			return List.copyOf(history);
		}
		return List.copyOf(history.subList(history.size() - limit, history.size()));
	}
}
