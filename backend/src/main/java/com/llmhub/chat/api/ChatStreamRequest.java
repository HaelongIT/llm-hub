package com.llmhub.chat.api;

import java.util.UUID;

/**
 * @param sessionId 없으면 새 세션을 만든다. 세션은 사용자 소유다 (S2).
 * @param question 검색 쿼리는 이 질문 그대로다. 쿼리 재작성은 v0 스코프 밖이다 (E3).
 */
public record ChatStreamRequest(UUID sessionId, String question) {}
