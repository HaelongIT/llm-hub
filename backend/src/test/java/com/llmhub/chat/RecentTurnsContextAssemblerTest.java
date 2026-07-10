package com.llmhub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * REQ-CHAT 시나리오: 후속 질문에 최근 N턴이 컨텍스트로 반영된다. {@code ContextAssembler} 교체 시 컨트롤러
 * 변경 없이 조립 전략만 바뀐다.
 *
 * <p>S2: LLM 컨텍스트는 최근 N턴(설정값). PERF-5: 무한 누적을 막는다.
 *
 * <p>E2: 조립은 교체 가능한 독립 컴포넌트다. 컨트롤러에 하드코딩하지 않는다.
 */
class RecentTurnsContextAssemblerTest {

	/** 토큰 예산이 관심사가 아닌 테스트용. */
	private static final int 넉넉한_예산 = 100_000;

	/** 메시지 하나를 고정 토큰 수로 세는 대역. TokenCountEstimator는 함수형 인터페이스가 아니다. */
	private static TokenCountEstimator 새(int 토큰) {
		return new TokenCountEstimator() {
			@Override
			public int estimate(String text) {
				return 토큰;
			}

			@Override
			public int estimate(org.springframework.ai.content.MediaContent content) {
				throw new UnsupportedOperationException();
			}

			@Override
			public int estimate(Iterable<org.springframework.ai.content.MediaContent> contents) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static final List<Message> 여섯_메시지 =
			List.of(
					Message.user("질문1"),
					Message.assistant("답변1"),
					Message.user("질문2"),
					Message.assistant("답변2"),
					Message.user("질문3"),
					Message.assistant("답변3"));

	@Test
	@DisplayName("최근 N턴만 컨텍스트에 담는다")
	void 최근_N턴만_담는다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(2, 넉넉한_예산);

		List<Message> 컨텍스트 = assembler.assemble(여섯_메시지);

		assertThat(컨텍스트)
				.as("한 턴은 질문과 답변 한 쌍이다. 2턴이면 메시지 4개다")
				.extracting(Message::content)
				.containsExactly("질문2", "답변2", "질문3", "답변3");
	}

	@Test
	@DisplayName("이력이 N턴보다 짧으면 전부 담는다")
	void 짧은_이력은_전부_담는다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(5, 넉넉한_예산);

		assertThat(assembler.assemble(여섯_메시지)).hasSize(6);
	}

	@Test
	@DisplayName("메시지 순서가 보존된다")
	void 순서가_보존된다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 넉넉한_예산);

		List<Message> 컨텍스트 = assembler.assemble(여섯_메시지);

		assertThat(컨텍스트).extracting(Message::role).containsExactly(
				Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT);
	}

	@Test
	@DisplayName("빈 이력은 빈 컨텍스트다")
	void 빈_이력은_빈_컨텍스트다() {
		assertThat(new RecentTurnsContextAssembler(3, 넉넉한_예산).assemble(List.of())).isEmpty();
	}

	@Test
	@DisplayName("홀수 개 메시지도 잘라낸 뒤 순서를 유지한다")
	void 홀수_메시지도_처리한다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(1, 넉넉한_예산);

		List<Message> 컨텍스트 = assembler.assemble(List.of(Message.user("혼자")));

		assertThat(컨텍스트).extracting(Message::content).containsExactly("혼자");
	}

	@Test
	@DisplayName("N턴을 바꾸면 조립 결과만 바뀐다 (E2)")
	void 전략_교체가_결과만_바꾼다() {
		assertThat(new RecentTurnsContextAssembler(1, 넉넉한_예산).assemble(여섯_메시지))
				.extracting(Message::content)
				.containsExactly("질문3", "답변3");
		assertThat(new RecentTurnsContextAssembler(3, 넉넉한_예산).assemble(여섯_메시지)).hasSize(6);
	}

	// PERF-5: "LLM 컨텍스트 길이 초과를 방지하는 상한(설정)".
	//
	// 턴 수만으로는 길이가 잡히지 않는다. 긴 메시지 하나면 컨텍스트가 넘친다.

	@Test
	@DisplayName("토큰 예산을 넘으면 오래된 턴부터 버린다")
	void 예산을_넘으면_오래된_턴부터_버린다() {
		// 각 메시지를 10토큰으로 세는 계수기. 예산 25면 최근 2개만 들어간다.
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 25, 새(10));

		List<Message> 컨텍스트 = assembler.assemble(여섯_메시지);

		assertThat(컨텍스트)
				.as("최근 대화가 가장 쓸모 있다. 버릴 것은 오래된 쪽이다")
				.extracting(Message::content)
				.containsExactly("질문3", "답변3");
	}

	@Test
	@DisplayName("예산이 넉넉하면 N턴을 그대로 담는다")
	void 예산이_넉넉하면_그대로_담는다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 1000, 새(10));

		assertThat(assembler.assemble(여섯_메시지)).hasSize(6);
	}

	@Test
	@DisplayName("메시지 하나가 예산보다 크면 컨텍스트는 비어 있다")
	void 예산보다_큰_메시지는_담지_않는다() {
		// 상한의 목적은 컨텍스트 초과 방지다. "최근 것은 무조건 담는다"면 상한이 아니다.
		// 지금 질문은 별도 상한이 지킨다(ChatController).
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 5, 새(100));

		assertThat(assembler.assemble(여섯_메시지)).isEmpty();
	}

	@Test
	@DisplayName("예산을 넘겨도 메시지 순서는 시간순이다")
	void 잘라낸_뒤에도_시간순이다() {
		// 10토큰 × 4개 = 40. 딱 4개가 들어간다.
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 40, 새(10));

		assertThat(assembler.assemble(여섯_메시지))
				.extracting(Message::content)
				.containsExactly("질문2", "답변2", "질문3", "답변3");
	}

	@Test
	@DisplayName("예산은 턴이 아니라 메시지 단위로 잘린다")
	void 예산은_메시지_단위로_잘린다() {
		// 35토큰이면 10토큰 메시지 3개만 들어간다. 반쪽 턴이 남을 수 있다 —
		// 상한의 목적은 대화 모양이 아니라 컨텍스트 초과 방지다.
		ContextAssembler assembler = new RecentTurnsContextAssembler(3, 35, 새(10));

		assertThat(assembler.assemble(여섯_메시지))
				.extracting(Message::content)
				.containsExactly("답변2", "질문3", "답변3");
	}
}
