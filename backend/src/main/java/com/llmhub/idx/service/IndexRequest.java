package com.llmhub.idx.service;

import java.util.List;
import java.util.UUID;

/**
 * 색인 요청. 진입 경로(API/CLI)와 무관하게 같은 서비스가 받는다 (S1, E1).
 *
 * @param docKey 교체 판단용 문서 식별 키. 같은 키면 통째 교체된다 (S17).
 * @param accessTags 이 문서의 접근 태그. 접근 태그의 유일한 원천이다 (S18).
 * @param uploadedBy 파일을 올린 사람({@code app_user.id}). 누가 어떤 문서를 올렸는지 남는 유일한 자리다
 *     (docs/03).
 */
public record IndexRequest(
		String docKey,
		String filename,
		String contentType,
		byte[] content,
		List<String> accessTags,
		UUID uploadedBy) {}
