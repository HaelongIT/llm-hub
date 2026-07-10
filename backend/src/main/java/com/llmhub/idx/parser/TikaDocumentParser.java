package com.llmhub.idx.parser;

import java.util.Locale;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;

/**
 * Tika 기본 포맷(PDF·docx·txt 등)을 다루는 어댑터 (S8-2).
 *
 * <p>hwp는 이 어댑터가 다루지 않는다. hwplib 어댑터의 몫이다.
 *
 * <p>Tika 타입은 이 클래스 밖으로 새어 나가지 않는다 (E8).
 */
public final class TikaDocumentParser implements DocumentParser {

	private static final Set<String> SUPPORTED = Set.of("txt", "pdf", "docx", "doc", "pptx", "xlsx", "html", "md");

	@Override
	public boolean supports(String extension) {
		return SUPPORTED.contains(extension.toLowerCase(Locale.ROOT));
	}

	@Override
	public String extractText(byte[] content, String filename) {
		if (content.length == 0) {
			throw new DocumentParseException("빈 파일에서 추출할 텍스트가 없다: " + filename);
		}
		String text;
		try {
			text = new TikaDocumentReader(new ByteArrayResource(content))
					.get()
					.stream()
					.map(Document::getText)
					.reduce((a, b) -> a + "\n" + b)
					.orElse("");
		} catch (RuntimeException e) {
			throw new DocumentParseException("텍스트 추출 실패: " + filename, e);
		}
		if (text.isBlank()) {
			throw new DocumentParseException("추출된 텍스트가 비어 있다: " + filename);
		}
		return text;
	}
}
