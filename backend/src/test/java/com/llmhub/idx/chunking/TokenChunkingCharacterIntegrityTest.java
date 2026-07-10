package com.llmhub.idx.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 청킹이 문자를 깨뜨리지 않는다는 불변식.
 *
 * <p>토큰 기반 분할기는 바이트 수준 BPE 토큰을 자르므로, 한 글자가 여러 토큰에 걸친 한국어에서 조각 경계가 글자 중간을 자를 수
 * 있다. 그러면 디코딩 결과에 U+FFFD(치환 문자)가 생기고, 손상된 텍스트가 그대로 {@code chunk_text}로 색인되어 검색과
 * 근거(sources)를 오염시킨다.
 *
 * <p>데이터 정합성 문제이므로 별도 불변식 테스트로 못박는다.
 */
class TokenChunkingCharacterIntegrityTest {

	private static final char 치환문자 = '�';

	private static final String 한국어_문서 =
			"연차휴가는 근로기준법에 따라 부여한다. 1년간 80퍼센트 이상 출근한 근로자에게 15일의 유급휴가를 준다. "
					+ "계속하여 근로한 기간이 1년 미만인 근로자에게는 1개월 개근 시 1일의 유급휴가를 준다. "
					+ "사용자는 근로자가 청구한 시기에 휴가를 주어야 한다. 다만 사업 운영에 막대한 지장이 있는 경우 시기를 변경할 수 있다. "
					+ "휴가는 1년간 행사하지 아니하면 소멸된다. 다만 사용자의 귀책사유로 사용하지 못한 경우에는 그러하지 아니하다.";

	@Test
	@DisplayName("조각 경계가 한국어 글자를 깨뜨리지 않는다")
	void 조각_경계가_글자를_깨뜨리지_않는다() {
		List<Chunk> chunks = new TokenChunkingStrategy(20).chunk(한국어_문서);

		assertThat(chunks)
				.as("손상된 글자가 색인되면 검색과 근거가 조용히 오염된다")
				.allSatisfy(c -> assertThat(c.text()).doesNotContain(String.valueOf(치환문자)));
	}
}
