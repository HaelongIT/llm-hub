package com.llmhub.idx.parser;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmhub.support.MinimalHwp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: hwp 업로드 → hwplib 경로로 추출·색인 성공.
 *
 * <p>S8-2: hwp는 Tika가 아니라 hwplib 어댑터가 다룬다. E8: 신규 포맷 추가가 파이프라인 구조를 바꾸지 않는다 —
 * {@link DocumentParser} 구현을 하나 더 넣을 뿐이다.
 */
class HwpDocumentParserTest {

	private final DocumentParser parser = new HwpDocumentParser();

	@Test
	@DisplayName("hwp 확장자만 지원한다")
	void hwp만_지원한다() {
		assertThat(parser.supports("hwp")).isTrue();
		assertThat(parser.supports("HWP")).isTrue();
		assertThat(parser.supports("pdf")).isFalse();
		assertThat(parser.supports("txt")).isFalse();
	}

	@Test
	@DisplayName("hwp에서 한국어 본문을 추출한다")
	void hwp에서_한국어를_추출한다() {
		byte[] hwp = MinimalHwp.withText("연차휴가는 근로기준법에 따라 부여한다.");

		String 추출 = parser.extractText(hwp, "인사규정.hwp");

		assertThat(추출).contains("연차휴가는 근로기준법에 따라 부여한다.");
		assertThat(추출).as("추출 결과가 손상되면 안 된다").doesNotContain("�");
	}

	@Test
	@DisplayName("hwp가 아닌 바이트는 파싱 실패로 거부한다")
	void 잘못된_바이트를_거부한다() {
		assertThatThrownBy(() -> parser.extractText("이건 hwp가 아니다".getBytes(UTF_8), "가짜.hwp"))
				.isInstanceOf(DocumentParseException.class);
	}

	@Test
	@DisplayName("본문이 비어 있으면 거부한다")
	void 빈_본문을_거부한다() {
		byte[] hwp = MinimalHwp.withText("   ");

		assertThatThrownBy(() -> parser.extractText(hwp, "빈문서.hwp"))
				.isInstanceOf(DocumentParseException.class);
	}
}
