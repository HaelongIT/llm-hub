package com.llmhub.idx.parser;

/**
 * 문서에서 텍스트를 추출한다. 포맷별 어댑터다 (E8).
 *
 * <p>신규 포맷 추가가 파이프라인 구조를 바꾸지 않아야 하므로, 이 계약에는 구현 라이브러리(Tika, hwplib)의 타입이 등장하지
 * 않는다.
 */
public interface DocumentParser {

	/** 이 어댑터가 해당 확장자를 다룰 수 있는가. 확장자는 소문자다. */
	boolean supports(String extension);

	/**
	 * @param content 원본 바이트
	 * @param filename 기록·진단용. 경로로 쓰지 않는다 (SEC-4).
	 * @return 추출된 전문
	 * @throws DocumentParseException 추출에 실패했거나 내용이 비어 있는 경우
	 */
	String extractText(byte[] content, String filename);
}
