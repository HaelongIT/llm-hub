/**
 * AUTH — 인증과 접근 태그 확정(앞단 게이트).
 *
 * <p>Keycloak JWT를 검증하고 RBAC 단계에서 역할을 접근 태그로 확정해 요청 컨텍스트에 싣는다.
 * 하위 계층(SEARCH·CHAT)은 이 태그를 소비만 하며 권한을 재판단하지 않는다(S4).
 *
 * @see <a href="../../../../../../docs/requirements/REQ-AUTH-auth-gate.md">REQ-AUTH</a>
 */
package com.llmhub.auth;
