package com.llmhub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-CHAT 시나리오: 후속 질문에 최근 N턴이 컨텍스트로 반영된다. {@code ContextAssembler} 교체 시 컨트롤러
 * 변경 없이 조립 전략만 바뀐다.
 *
 * <p>S2: LLM 컨텍스트는 최근 N턴(설정값). PERF-5: 무한 누적을 막는다.
 *
 * <p>E2: 조립은 교체 가능한 독립 컴포넌트다. 컨트롤러에 하드코딩하지 않는다.
 */
class RecentTurnsContextAssemblerTest {

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
		ContextAssembler assembler = new RecentTurnsContextAssembler(2);

		List<Message> 컨텍스트 = assembler.assemble(여섯_메시지);

		assertThat(컨텍스트)
				.as("한 턴은 질문과 답변 한 쌍이다. 2턴이면 메시지 4개다")
				.extracting(Message::content)
				.containsExactly("질문2", "답변2", "질문3", "답변3");
	}

	@Test
	@DisplayName("이력이 N턴보다 짧으면 전부 담는다")
	void 짧은_이력은_전부_담는다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(5);

		assertThat(assembler.assemble(여섯_메시지)).hasSize(6);
	}

	@Test
	@DisplayName("메시지 순서가 보존된다")
	void 순서가_보존된다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(3);

		List<Message> 컨텍스트 = assembler.assemble(여섯_메시지);

		assertThat(컨텍스트).extracting(Message::role).containsExactly(
				Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT);
	}

	@Test
	@DisplayName("빈 이력은 빈 컨텍스트다")
	void 빈_이력은_빈_컨텍스트다() {
		assertThat(new RecentTurnsContextAssembler(3).assemble(List.of())).isEmpty();
	}

	@Test
	@DisplayName("홀수 개 메시지도 잘라낸 뒤 순서를 유지한다")
	void 홀수_메시지도_처리한다() {
		ContextAssembler assembler = new RecentTurnsContextAssembler(1);

		List<Message> 컨텍스트 = assembler.assemble(List.of(Message.user("혼자")));

		assertThat(컨텍스트).extracting(Message::content).containsExactly("혼자");
	}

	@Test
	@DisplayName("N턴을 바꾸면 조립 결과만 바뀐다 (E2)")
	void 전략_교체가_결과만_바꾼다() {
		assertThat(new RecentTurnsContextAssembler(1).assemble(여섯_메시지))
				.extracting(Message::content)
				.containsExactly("질문3", "답변3");
		assertThat(new RecentTurnsContextAssembler(3).assemble(여섯_메시지)).hasSize(6);
	}
}
