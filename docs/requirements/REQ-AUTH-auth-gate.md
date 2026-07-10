# REQ-AUTH · 인증 + 접근 태그 게이트

## 책임
Keycloak으로 인증하고, 요청이 경로 분기되기 전에 사용자의 접근 태그를 확정해 요청 컨텍스트에 싣는다(앞단 게이트).

## 관련 결정
S3, S4, S25 · 경계 E4 · NFR SEC-1, SEC-2

## 인터페이스
- `RoleTagMapper` — 입력: Keycloak 역할 집합. 출력: 접근 태그 집합. **단일 컴포넌트**. v0: USER→{public}, ADMIN→{public, restricted}. (E4)
- 요청 컨텍스트에 태그를 싣는 수단(예: ServerWebExchange attribute / SecurityContext).

## 기능 명세
1. **필터체인 순서** — CORS → JWT → RBAC → RateLimit. 순서 준수.
2. **JWT 검증** — Keycloak 발급 토큰 검증 → 인증 주체 확립. 실패 시 401.
3. **역할 추출** — Realm Role → 권한. 
4. **태그 확정(게이트)** — RBAC 단계에서 `RoleTagMapper`로 접근 태그 집합 산출 → **요청 컨텍스트에 적재**. 이후 계층은 이 태그를 소비만.
5. **RateLimit 자리** — 통과 또는 느슨한 기본값(v0 정책 없음).
6. **태그 어휘 일관성** — 산출 태그는 document 태그와 **동일 어휘**(S18과 공유). 별도 변환 계층 만들지 말 것.

## 불변식
- 인증 안 된 요청은 어떤 하위 계층에도 도달하지 않는다.
- 태그 확정은 RAG/검색 진입 **이전**에 완료된다.
- 하위 계층(SEARCH/CHAT)은 태그를 재계산하지 않는다.

## 테스트 시나리오 (TDD)
- [ ] 유효 JWT → 인증 성공, 태그가 컨텍스트에 실림.
- [ ] 무효/만료 JWT → 401.
- [x] USER 역할 → {public} 태그.
- [x] ADMIN 역할 → {public, restricted} 태그.
- [ ] 태그 확정이 검색 호출보다 먼저 일어남(순서 검증).
- [ ] 필터체인 실행 순서가 CORS→JWT→RBAC→RateLimit.
- [x] `RoleTagMapper` 매핑 변경 시 다른 코드 변경 없이 태그 결과만 바뀜.

## 하지 않을 것 (v0)
3역할 이상, 부서·그룹 기반 매핑, 계층형 역할, 개인 지정 공유. — `RoleTagMapper` 교체로 나중에 수용.
