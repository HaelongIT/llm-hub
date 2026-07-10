package com.llmhub.support;

import java.io.ByteArrayOutputStream;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

/**
 * 테스트용 최소 hwp 문서를 바이트로 만든다.
 *
 * <p>한계: 픽스처를 hwplib으로 만들고 hwplib으로 읽으므로, 이 테스트는 <b>어댑터 배선</b>을 검증하지 실제 한글
 * 워드프로세서가 저장한 파일과의 호환성을 검증하지 않는다. 후자는 진짜 .hwp 샘플이 있어야 한다.
 *
 * <p>그래도 검증할 가치가 있다. hwp 경로가 Tika가 아니라 hwplib 어댑터로 갈라지는지, 추출된 한국어가 손상되지 않는지,
 * 파이프라인이 hwp를 끝까지 통과시키는지를 확인한다.
 */
public final class MinimalHwp {

	private MinimalHwp() {}

	public static byte[] withText(String text) {
		try {
			HWPFile hwp = BlankFileMaker.make();
			Paragraph paragraph = hwp.getBodyText().getSectionList().get(0).getParagraph(0);
			paragraph.getText().addString(text);
			paragraph.getHeader().setLastInList(true);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			HWPWriter.toStream(hwp, out);
			return out.toByteArray();
		} catch (Exception e) {
			throw new IllegalStateException("hwp 픽스처 생성 실패", e);
		}
	}
}
