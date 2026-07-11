package com.llmhub.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세션 제목 생성이 글자(코드포인트) 경계를 지키는지 본다 (S2).
 *
 * <p>TokenChunkingStrategy가 청킹에서 코드포인트 경계를 지키듯, 제목 절단도 UTF-16 서로게이트 쌍을 쪼개면 안 된다.
 */
class TitleOfTest {

	@Test
	@DisplayName("60자 이하 질문은 그대로 제목이 된다")
	void 짧은_질문은_그대로다() {
		assertThat(ChatController.titleOf("연차휴가는 며칠인가요?")).isEqualTo("연차휴가는 며칠인가요?");
	}

	@Test
	@DisplayName("긴 질문을 잘라도 서로게이트 쌍을 쪼개지 않는다 (리뷰 F6)")
	void 서로게이트_쌍을_쪼개지_않는다() {
		// 인덱스 59에 high surrogate가 오도록 BMP 59자 + 보조평면 문자(2 UTF-16 unit)를 둔다.
		// 소스 인코딩에 흔들리지 않게 코드포인트로 만든다 (U+1D518, 𝔘).
		String 보조평면 = new String(Character.toChars(0x1D518));
		String 질문 = "가".repeat(59) + 보조평면 + "나".repeat(10);

		String 제목 = ChatController.titleOf(질문);

		// 고립 서로게이트가 있으면 UTF-8 왕복에서 U+FFFD로 바뀌어 원본과 달라진다.
		String 왕복 = new String(제목.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		assertThat(왕복).as("잘린 제목에 고립 서로게이트가 없어야 한다").isEqualTo(제목);
		assertThat(제목.length()).isLessThanOrEqualTo(60);
	}
}
