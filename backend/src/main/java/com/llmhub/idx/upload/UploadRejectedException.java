package com.llmhub.idx.upload;

/** 업로드가 허용목록·크기 검증에 걸려 거부되었다 (SEC-4). */
public class UploadRejectedException extends RuntimeException {

	public UploadRejectedException(String message) {
		super(message);
	}
}
