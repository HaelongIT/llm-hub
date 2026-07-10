package com.llmhub.idx.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 재색인하려는 {@code doc_key}의 document가 없다. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DocumentNotFoundException extends RuntimeException {

	public DocumentNotFoundException(String docKey) {
		super("해당 doc_key의 문서가 없다: " + docKey);
	}
}
