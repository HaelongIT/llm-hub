package com.llmhub.chat;

import java.util.List;

/**
 * 세션 이력에서 LLM 컨텍스트를 조립한다.
 *
 * <p>교체 가능한 독립 컴포넌트다. 컨트롤러에 하드코딩하지 않는다 (E2). 요약 기반 조립이나 토큰 예산 기반 조립으로 갈아끼워도
 * 컨트롤러는 바뀌지 않는다.
 */
public interface ContextAssembler {

	/**
	 * @param history 오래된 것부터 정렬된 세션 이력
	 * @return LLM에 넘길 메시지. 순서는 보존된다.
	 */
	List<Message> assemble(List<Message> history);
}
