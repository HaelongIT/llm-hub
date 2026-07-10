package com.llmhub.chat;

import com.llmhub.search.Source;
import java.util.List;

/**
 * 이벤트 타입 SSE (S6). 클라이언트는 이 네 가지만 본다.
 *
 * <p>{@link Sources}는 <b>서버 검색 결과에서 직접</b> 생성된다. LLM 출력에서 근거를 파싱하지 않는다. 그래서
 * 프롬프트 인젝션이 근거를 위조할 수 없다.
 */
public sealed interface ChatEvent {

	/** 답변 조각. 점진 전달로 체감 지연을 줄인다 (PERF-2). */
	record Text(String delta) implements ChatEvent {}

	/** 근거. 첫 {@link Text}보다 먼저 발행된다. */
	record Sources(List<Source> sources) implements ChatEvent {}

	/** 장애 시 명시적 종료. 무한 대기·행을 막는다 (S8-3, REL-1). */
	record Error(String message) implements ChatEvent {}

	/** 완료 + 추적 ID (REL-3). */
	record Done(String traceId) implements ChatEvent {}
}
