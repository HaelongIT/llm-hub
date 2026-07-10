package com.llmhub.idx.parser;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: txt 문서에서 텍스트를 추출한다.
 *
 * <p>E8: 파서는 포맷별 교체·추가 가능한 어댑터다. 신규 포맷 추가가 파이프라인 구조를 바꾸지 않아야 하므로, {@link
 * DocumentParser} 계약에 Tika 타입이 등장하지 않는다.
 */
class TikaDocumentParserTest {

	private final DocumentParser parser = new TikaDocumentParser();

	@Test
	@DisplayName("txt 확장자를 지원한다")
	void txt를_지원한다() {
		assertThat(parser.supports("txt")).isTrue();
		assertThat(parser.supports("pdf")).isTrue();
	}

	@Test
	@DisplayName("hwp는 지원하지 않는다 (hwplib 어댑터의 몫이다)")
	void hwp는_지원하지_않는다() {
		assertThat(parser.supports("hwp")).isFalse();
	}

	@Test
	@DisplayName("txt에서 한국어 본문을 추출한다")
	void txt에서_한국어를_추출한다() {
		byte[] 원본 = "연차휴가는 근로기준법에 따라 부여한다.\n1년간 80퍼센트 이상 출근한 근로자에게 15일을 준다.".getBytes(UTF_8);

		String 추출 = parser.extractText(원본, "인사규정.txt");

		assertThat(추출).contains("연차휴가는 근로기준법에 따라 부여한다.");
		assertThat(추출).contains("15일을 준다.");
		assertThat(추출).as("추출 결과가 손상되면 안 된다").doesNotContain("�");
	}

	@Test
	@DisplayName("빈 문서는 거부한다")
	void 빈_문서를_거부한다() {
		assertThatThrownBy(() -> parser.extractText(new byte[0], "빈파일.txt"))
				.as("추출된 텍스트가 없으면 색인할 조각이 하나도 없다. 조용히 성공하면 안 된다")
				.isInstanceOf(DocumentParseException.class);
	}

	@Test
	@DisplayName("내용이 공백뿐이면 거부한다")
	void 공백뿐인_문서를_거부한다() {
		assertThatThrownBy(() -> parser.extractText("   \n\t  ".getBytes(UTF_8), "공백.txt"))
				.isInstanceOf(DocumentParseException.class);
	}
}
