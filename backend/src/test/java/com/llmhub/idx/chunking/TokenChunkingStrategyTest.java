package com.llmhub.idx.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: 텍스트를 토큰 크기 기준으로 기계적으로 청킹하고, 각 조각에 위치정보를 부여한다.
 *
 * <p>S12: 도메인 중립 청킹이다. "조항 단위" 같은 도메인 청킹을 하지 않는다. E11: 전략은 교체 가능하다 — 테스트는
 * {@link ChunkingStrategy} 계약에만 의존하고 구현 라이브러리를 알지 못한다.
 */
class TokenChunkingStrategyTest {

	/** 한 문장이 대략 한 조각에 들어가도록 작은 청크 크기를 쓴다. */
	private final ChunkingStrategy strategy = new TokenChunkingStrategy(20);

	private static final String 긴_문서 =
			"""
			연차휴가는 근로기준법에 따라 부여한다. 1년간 80퍼센트 이상 출근한 근로자에게 15일의 유급휴가를 준다.
			계속하여 근로한 기간이 1년 미만인 근로자에게는 1개월 개근 시 1일의 유급휴가를 준다.
			사용자는 근로자가 청구한 시기에 휴가를 주어야 한다. 다만 사업 운영에 막대한 지장이 있는 경우 시기를 변경할 수 있다.
			휴가는 1년간 행사하지 아니하면 소멸된다. 다만 사용자의 귀책사유로 사용하지 못한 경우에는 그러하지 아니하다.
			""";

	@Test
	@DisplayName("긴 텍스트를 여러 조각으로 나눈다")
	void 긴_텍스트를_여러_조각으로_나눈다() {
		List<Chunk> chunks = strategy.chunk(긴_문서);

		assertThat(chunks).as("작은 청크 크기라면 한 조각으로 뭉쳐질 수 없다").hasSizeGreaterThan(1);
	}

	@Test
	@DisplayName("각 조각에 순번 위치정보가 0부터 순서대로 붙는다")
	void 각_조각에_위치정보가_붙는다() {
		List<Chunk> chunks = strategy.chunk(긴_문서);

		assertThat(chunks).extracting(Chunk::location).doesNotContainNull();
		assertThat(chunks)
				.as("위치정보는 조각의 순서를 복원할 수 있어야 한다 (S7: location)")
				.extracting(Chunk::location)
				.containsExactlyElementsOf(순번_리스트(chunks.size()));
	}

	@Test
	@DisplayName("조각 텍스트는 비어 있지 않다")
	void 조각_텍스트는_비어_있지_않다() {
		List<Chunk> chunks = strategy.chunk(긴_문서);

		assertThat(chunks).extracting(Chunk::text).allSatisfy(t -> assertThat(t).isNotBlank());
	}

	@Test
	@DisplayName("원문의 내용이 조각들에 보존된다")
	void 원문_내용이_보존된다() {
		List<Chunk> chunks = strategy.chunk(긴_문서);

		String 합친_조각 = chunks.stream().map(Chunk::text).reduce("", String::concat);
		assertThat(공백제거(합친_조각))
				.as("청킹은 내용을 버리지 않는다. 버리면 검색되지 않는 원문이 생긴다")
				.contains(공백제거("연차휴가는 근로기준법에 따라 부여한다"))
				.contains(공백제거("휴가는 1년간 행사하지 아니하면 소멸된다"));
	}

	@Test
	@DisplayName("짧은 텍스트는 한 조각이 된다")
	void 짧은_텍스트는_한_조각이_된다() {
		List<Chunk> chunks = strategy.chunk("연차휴가는 15일이다.");

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).location()).isEqualTo("0");
	}

	@Test
	@DisplayName("빈 텍스트는 조각을 만들지 않는다")
	void 빈_텍스트는_조각을_만들지_않는다() {
		assertThat(strategy.chunk("   ")).isEmpty();
	}

	private static List<String> 순번_리스트(int size) {
		return java.util.stream.IntStream.range(0, size).mapToObj(String::valueOf).toList();
	}

	private static String 공백제거(String s) {
		return s.replaceAll("\\s+", "");
	}
}
