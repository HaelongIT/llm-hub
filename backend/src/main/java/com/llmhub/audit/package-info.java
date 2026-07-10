/**
 * AUDIT — 감사 로그. 대화 이력과 독립적인 수명주기.
 *
 * <p>CHAT 응답 완료 시 비동기로 기록된다. 어떤 모듈에도 의존하지 않는다.
 * audit_log에는 FK가 없다. 사용자·세션 삭제와 무관하게 유지된다(S5).
 *
 * @see <a href="../../../../../../docs/requirements/REQ-AUDIT-logging.md">REQ-AUDIT</a>
 */
package com.llmhub.audit;
