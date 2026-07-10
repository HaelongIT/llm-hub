# CLAUDE.md — 프로젝트 헌법

> 이 파일은 **모든 작업의 최상위 규칙**이다. 코드 작성 전 항상 이 파일과 `docs/`를 읽는다.
> 규칙이 서로 충돌하면: **보안 > 데이터 정합성 > 결정 준수(docs/02) > 테스트 통과 > 기능 완성 > 속도**.

---

## 1. 이 프로젝트가 무엇인가

**도메인 중립 RAG 챗 런타임.** 회사별 설치형 배포. 인증·권한·감사가 내장된, 문서 검색 기반 채팅 시스템.

- **코어는 도메인을 모른다.** 규정·인사·ERP 같은 특정 업무 개념을 코어에 넣지 않는다.
- **언어는 배포 설정이다.** v0는 한국어(검색 분석기 nori). 도메인 중립 ≠ 언어 중립.
- **v0는 뼈대다.** 아래 흐름이 end-to-end로 동작하는 것이 목표. 그 이상은 만들지 않되, 확장을 구조적으로 막지도 않는다.

```
문서 색인(관리자) → 로그인 → 질문 → 접근태그 필터된 하이브리드 검색 → 근거 포함 스트리밍 응답 → 감사 기록
```

전체 정체성·스코프는 `docs/00-overview.md`. **이 스코프를 벗어나는 기능을 임의로 추가하지 않는다.**

---

## 2. 기술 스택 (확정 — 임의 변경 금지)

| 계층 | 기술 |
|---|---|
| Client | Next.js (App Router) · Vercel AI SDK (useChat) · zustand · next-auth(Keycloak) |
| Edge | Spring Security 필터체인 · Keycloak (OIDC) |
| Core | Spring Boot **WebFlux** · Spring AI ChatClient |
| Data | PostgreSQL (JPA/Hibernate · Flyway) · Elasticsearch (dense_vector + BM25, nori 분석기) |
| ETL | Apache Tika · hwplib · TokenTextSplitter · EmbeddingModel(게이트웨이 경유) |
| Infra | LiteLLM Gateway · Ollama / OpenAI · Anthropic · Google |

- 스택 변경이 필요하다고 판단되면 **코드를 짜지 말고 먼저 사람에게 확인**한다.
- WebFlux(논블로킹)와 JPA(블로킹)가 공존한다. **블로킹 DB 접근은 반드시 전용 리포지토리 계층에서 격리**한다(`docs/02` E12, `docs/04` 참조).

---

## 3. 절대 규칙 (위반 시 즉시 중단)

1. **결정 문서를 어기지 않는다.** `docs/02-decisions.md`의 S(결정)·E(경계)는 구현 제약이다. 어겨야 할 것 같으면 코드 대신 질문한다.
2. **권한은 구조로 강제한다.** 접근 태그 확정은 앞단 게이트에서만(S4). RAG Advisor·검색 계층은 태그를 **소비만** 하고 권한을 판단하지 않는다.
3. **근거는 서버 검색 결과에서만 나온다.** LLM 출력에서 근거를 파싱하지 않는다(S6). `sources`는 실제 검색된 조각에서 서버가 생성한다.
4. **감사 로그는 사용자 삭제와 무관하게 남는다.** 대화 이력과 별도 테이블, FK 없음(S5).
5. **색인/검색 임베딩 모델은 동일해야 한다.** 임베딩 모델은 설정값으로 고정하고 조각 메타데이터에 기록한다(S8-4).
6. **비밀정보를 커밋하지 않는다.** 키·토큰·비밀번호는 `.env`(gitignore). `.env.example`만 커밋.
7. **테스트 없이 기능 코드를 병합하지 않는다.** TDD 절차(`docs/05`)를 따른다.

---

## 4. 작업 워크플로우 (매 작업 반복)

1. **읽기** — `CLAUDE.md` + 관련 `docs/`(특히 해당 `requirements/REQ-*.md`와 `docs/02-decisions.md`)를 먼저 읽는다.
2. **계획** — 무엇을 어느 모듈에 만들지, 어떤 경계(E)를 지켜야 하는지 확인. 불명확하면 질문.
3. **TDD** — `docs/05-tdd-workflow.md`대로 **테스트 먼저 → 실패 확인 → 구현 → 통과 → 리팩터**.
4. **교훈 기록** — 테스트/구현 중 얻은 교훈을 `docs/06-memory-protocol.md`대로 `docs/LEARNINGS.md`에 남긴다.
5. **커밋** — `docs/07-git-workflow.md`의 단위·컨벤션으로 커밋한다.

---

## 5. 모듈 지도 (= TDD 단위 = 요구사항)

각 모듈은 독립적으로 테스트 가능해야 한다. 모듈 간 통신은 인터페이스(계약)로만.

| 모듈 | 요구사항 문서 | 책임 |
|---|---|---|
| **IDX** 색인 | `requirements/REQ-IDX-indexing.md` | 업로드→추출→청킹→임베딩→저장, 원본 보관, 재색인/교체 |
| **AUTH** 인증·게이트 | `requirements/REQ-AUTH-auth-gate.md` | Keycloak 검증, 역할→접근태그 확정(앞단 게이트) |
| **SEARCH** 검색 | `requirements/REQ-SEARCH-retrieval.md` | 하이브리드 검색(BM25+벡터), 접근태그 필터, sources 생성 |
| **CHAT** 채팅 | `requirements/REQ-CHAT-streaming.md` | ChatClient 오케스트레이션, 이벤트 SSE, 세션·이력 |
| **AUDIT** 감사 | `requirements/REQ-AUDIT-logging.md` | 감사 로그 기록(독립 수명주기) |

**의존 방향:** CHAT → (AUTH가 확정한 태그 소비) → SEARCH → 근거 → 응답. AUDIT는 CHAT 완료 시 비동기 기록. IDX는 독립(관리자 경로).

---

## 6. 커밋·메모리 규율 (요약 — 상세는 docs/07, docs/06)

- **커밋:** 모듈 내 논리 단위마다. 테스트+구현을 함께. 메시지는 Conventional Commits(`feat:`, `test:`, `fix:`, `refactor:`, `docs:`, `chore:`).
- **메모리:** 매 TDD 사이클에서 배운 것(라이브러리 실제 동작, 경계 구현 방식, 함정)을 `docs/LEARNINGS.md`에 append. 다음 세션은 이 파일을 읽고 시작한다.

---

## 7. 지금 하지 말 것 (v0 스코프 밖)

판단 게이트, ERP 연동, 규칙 엔진, 능동 알림, 관리자 UI, 모델 선택 UI, 쿼리 재작성, 자동 페일오버, 대화 자동 만료, 문서 생성·편집(06 레이어). — 이들의 "자리"는 열어두되(경계 E 참조) **구현하지 않는다.** 필요해 보이면 질문한다.
