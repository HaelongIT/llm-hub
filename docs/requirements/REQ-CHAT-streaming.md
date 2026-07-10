# REQ-CHAT · 채팅 스트리밍 + 세션·이력

## 책임
질문을 받아 컨텍스트를 조립하고, RAG로 근거 기반 답변을 생성해 이벤트 SSE로 스트리밍한다. 세션·이력을 관리한다.

## 관련 결정
S2, S6, S8-1, S8-3, S13 · 경계 E2, E3, E7, E13 · NFR PERF-1, PERF-2, REL-1

## 인터페이스
- `ContextAssembler` — 세션 이력 → 최근 N턴 컨텍스트. 교체 가능. (E2)
- `ChatClient`(Spring AI) — RAG Advisor 체인 호출. **RAG 없는 호출도 가능한 구조**(E13).
- `SessionRepository`, `MessageRepository`(PG, 블로킹 → 격리 계층).

## 기능 명세
1. **엔드포인트** — `POST /api/chat/stream` (WebFlux, `Flux<ServerSentEvent<…>>`).
2. **컨텍스트 조립** — `ContextAssembler`로 최근 N턴(설정값). 컨트롤러에 하드코딩 금지.
3. **RAG 호출** — `ChatClient.prompt().advisors(RAG 체인).stream()`. 단일 고정 LLM(S8-1). 모델명은 게이트웨이 파라미터(E7).
4. **이벤트 스트림(S6)** — 이벤트 타입:
   - `text` — 답변 조각(점진 전달)
   - `sources` — SEARCH가 생성한 근거(서버 검색 결과)
   - `error` — 장애 시 명시적 종료(S8-3)
   - `done` — 완료 + trace_id
5. **이력 저장** — 스트림 완료 시 user/assistant 메시지 저장. **격리 스케줄러로 넘기고 스트림은 대기하지 않음**(S13). assistant 메시지에 sources 스냅샷.
6. **세션 관리** — 생성·조회·삭제. 삭제 시 메시지 cascade(감사는 별도라 유지).
7. **장애** — LLM/ES 다운 시 `error` 이벤트로 종료. 부분 응답 후 끊김을 사용자가 구분 가능.

## 불변식
- 논블로킹 스트림 흐름이 블로킹 저장에 의해 막히지 않는다(S13).
- 저장 실패는 응답 성공을 되돌리지 않는다(로깅).
- 근거는 SEARCH가 만든 sources를 그대로 전달(CHAT이 새로 만들지 않음).
- 세션 삭제가 감사 로그에 영향을 주지 않는다.

## 테스트 시나리오 (TDD)
- [x] 질문 → text 조각들이 순차 스트리밍되고 done으로 종료.
- [x] 응답에 sources 이벤트가 포함되고, 그 내용이 SEARCH 결과와 일치.
- [x] 후속 질문에 최근 N턴이 컨텍스트로 반영됨.
- [x] LLM 장애 주입 → error 이벤트로 스트림 종료(행 없음).
- [ ] 스트림 완료 후 user/assistant 메시지가 저장됨(sources 스냅샷 포함).
- [ ] 저장을 강제 실패시켜도 사용자는 정상 응답을 받음(응답 롤백 없음).
- [ ] 블로킹 저장이 논블로킹 스레드를 점유하지 않음(스케줄러 격리 검증).
- [ ] 세션 삭제 → 메시지 사라짐, 해당 대화의 감사 로그는 남음.
- [x] `ContextAssembler` 교체 시 컨트롤러 변경 없이 조립 전략만 바뀜.

## 하지 않을 것 (v0)
쿼리 재작성, 모델 선택 UI, 대화 자동 만료, 판단 게이트, 되묻기. — 자리만.
