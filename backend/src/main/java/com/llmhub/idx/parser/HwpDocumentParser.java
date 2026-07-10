package com.llmhub.idx.parser;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;

/**
 * 한글(hwp) 문서를 다루는 어댑터 (S8-2).
 *
 * <p>Tika는 hwp를 다루지 않으므로 hwplib으로 갈라진다. 파이프라인 구조는 그대로다 — {@link DocumentParser}
 * 구현이 하나 더 있을 뿐이다 (E8).
 *
 * <p>hwplib 타입은 이 클래스 밖으로 새어 나가지 않는다.
 */
public final class HwpDocumentParser implements DocumentParser {

	private static final String EXTENSION = "hwp";

	@Override
	public boolean supports(String extension) {
		return EXTENSION.equals(extension.toLowerCase(Locale.ROOT));
	}

	@Override
	public String extractText(byte[] content, String filename) {
		if (content.length == 0) {
			throw new DocumentParseException("빈 파일에서 추출할 텍스트가 없다: " + filename);
		}
		String text;
		try {
			HWPFile hwp = HWPReader.fromInputStream(new ByteArrayInputStream(content));
			text = TextExtractor.extract(hwp, TextExtractMethod.OnlyMainParagraph);
		} catch (Exception e) {
			throw new DocumentParseException("hwp 추출 실패: " + filename, e);
		}
		if (text == null || text.isBlank()) {
			throw new DocumentParseException("추출된 텍스트가 비어 있다: " + filename);
		}
		return text;
	}
}
