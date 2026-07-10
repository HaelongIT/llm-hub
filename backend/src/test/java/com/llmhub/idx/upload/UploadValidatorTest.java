package com.llmhub.idx.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: 허용되지 않은 확장자는 거부한다.
 *
 * <p>SEC-4는 업로드에 대해 크기·확장자·MIME을 **허용목록 방식**으로 검증하라고 요구한다. 차단목록이 아니라 허용목록이어야
 * 새 포맷이 조용히 통과하지 않는다.
 */
class UploadValidatorTest {

	private static final long 최대_바이트 = 100;

	/** 확장자 → 허용 MIME 집합. 설정값이며 코드에 하드코딩하지 않는다 (REL-4). */
	private static final Map<String, Set<String>> 허용목록 =
			Map.of(
					"pdf", Set.of("application/pdf"),
					"txt", Set.of("text/plain"),
					"hwp", Set.of("application/x-hwp", "application/haansofthwp"));

	private final UploadValidator validator = new UploadValidator(허용목록, 최대_바이트);

	@Test
	@DisplayName("허용된 확장자·MIME·크기면 통과한다")
	void 허용된_업로드는_통과한다() {
		assertThatCode(() -> validator.validate("규정.pdf", "application/pdf", 최대_바이트))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("허용되지 않은 확장자는 거부한다")
	void 허용되지_않은_확장자를_거부한다() {
		assertThatThrownBy(() -> validator.validate("악성.exe", "application/octet-stream", 10))
				.isInstanceOf(UploadRejectedException.class)
				.hasMessageContaining("exe");
	}

	@Test
	@DisplayName("확장자를 위장해도 MIME이 허용목록과 다르면 거부한다")
	void MIME_불일치를_거부한다() {
		assertThatThrownBy(() -> validator.validate("악성.pdf", "application/x-msdownload", 10))
				.isInstanceOf(UploadRejectedException.class)
				.hasMessageContaining("application/x-msdownload");
	}

	@Test
	@DisplayName("이중 확장자는 마지막 확장자로 판정한다")
	void 이중_확장자는_마지막으로_판정한다() {
		assertThatThrownBy(() -> validator.validate("규정.pdf.exe", "application/pdf", 10))
				.isInstanceOf(UploadRejectedException.class)
				.hasMessageContaining("exe");
	}

	@Test
	@DisplayName("확장자가 없으면 거부한다")
	void 확장자가_없으면_거부한다() {
		assertThatThrownBy(() -> validator.validate("규정", "application/pdf", 10))
				.isInstanceOf(UploadRejectedException.class);
	}

	@Test
	@DisplayName("확장자 대소문자는 구분하지 않는다")
	void 확장자_대소문자를_구분하지_않는다() {
		assertThatCode(() -> validator.validate("규정.PDF", "application/pdf", 10))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("최대 크기를 넘으면 거부한다")
	void 크기_초과를_거부한다() {
		assertThatThrownBy(() -> validator.validate("규정.pdf", "application/pdf", 최대_바이트 + 1))
				.isInstanceOf(UploadRejectedException.class);
	}

	@Test
	@DisplayName("빈 파일은 거부한다")
	void 빈_파일을_거부한다() {
		assertThatThrownBy(() -> validator.validate("규정.pdf", "application/pdf", 0))
				.isInstanceOf(UploadRejectedException.class);
	}
}
