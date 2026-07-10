package com.llmhub.idx.service;

/** 색인 결과. */
public record IndexResult(String documentId, String indexingRunId, int chunkCount) {}
