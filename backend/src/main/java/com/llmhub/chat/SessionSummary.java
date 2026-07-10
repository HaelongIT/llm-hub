package com.llmhub.chat;

import java.time.Instant;
import java.util.UUID;

/** 사이드바에 그릴 세션 한 줄 (S2). */
public record SessionSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {}
