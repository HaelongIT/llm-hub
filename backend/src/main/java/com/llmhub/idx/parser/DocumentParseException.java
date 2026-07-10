package com.llmhub.idx.parser;

/** 문서에서 색인할 텍스트를 얻지 못했다. */
public class DocumentParseException extends RuntimeException {

	public DocumentParseException(String message) {
		super(message);
	}

	public DocumentParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
