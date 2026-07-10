package com.llmhub.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트용 최소 PDF를 바이트로 만든다.
 *
 * <p>PDF 픽스처 파일을 저장소에 넣거나 PDF 생성 라이브러리를 의존성에 추가하는 대신, 규격대로 직접 조립한다. 내용이 코드로
 * 드러나므로 리뷰 가능하고, 의존성이 늘지 않는다.
 *
 * <p>본문은 ASCII만 담는다. PDF에 한국어를 넣으려면 폰트 임베딩이 필요한데, 이 테스트가 확인하려는 것은 추출 경로이지 폰트가
 * 아니다.
 */
public final class MinimalPdf {

	private MinimalPdf() {}

	public static byte[] withText(String asciiText) {
		String contentStream = "BT /F1 12 Tf 20 100 Td (" + asciiText + ") Tj ET\n";

		List<String> objects = List.of(
				"<< /Type /Catalog /Pages 2 0 R >>",
				"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
				"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R"
						+ " /Resources << /Font << /F1 5 0 R >> >> >>",
				"<< /Length " + contentStream.length() + " >>\nstream\n" + contentStream + "endstream",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();

		write(out, "%PDF-1.4\n");
		for (int i = 0; i < objects.size(); i++) {
			offsets.add(out.size());
			write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
		}

		int xrefOffset = out.size();
		StringBuilder xref = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
		for (int offset : offsets) {
			xref.append("%010d 00000 n \n".formatted(offset));
		}
		write(out, xref.toString());
		write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\n");
		write(out, "startxref\n" + xrefOffset + "\n%%EOF\n");

		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
	}
}
