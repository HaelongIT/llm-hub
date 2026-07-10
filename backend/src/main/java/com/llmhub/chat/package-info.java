/**
 * CHAT — 컨텍스트 조립, RAG 오케스트레이션, 이벤트 SSE 스트리밍, 세션·이력.
 *
 * <p>SEARCH를 명시적으로 호출해 근거를 얻고, 그것을 그대로 sources 이벤트로 전달한다.
 * 이력·감사 저장은 격리 스케줄러로 넘기고 스트림은 대기하지 않는다(S13).
 *
 * @see <a href="../../../../../../docs/requirements/REQ-CHAT-streaming.md">REQ-CHAT</a>
 */
package com.llmhub.chat;
