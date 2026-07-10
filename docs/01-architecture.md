# 01 · 아키텍처

## 계층 구조

```
CLIENT  →  EDGE  →  CORE  →  DATA·RAG  →  INFRA
```

요청은 위에서 아래로 흐른다.

### 01 · CLIENT (Next.js)
- App Router, 사이드바 레이아웃, zustand로 세션 상태.
- Vercel AI SDK `useChat`(`@ai-sdk/react`) — AI SDK의 데이터 스트림 프로토콜을 파싱한다.
- **BFF가 프로토콜을 번역한다.** `useChat`은 SSE의 `event:` 이름이 아니라 `data:` 안 JSON의 `type` 필드로 파트를 구분하므로, 코어가 내보내는 명명된 이벤트(`text`/`sources`/`error`/`done`)를 BFF에서 AI SDK 파트(`text-delta`/`data-sources`/`error`/`finish`)로 옮긴다. 응답에 `x-vercel-ai-ui-message-stream: v1` 헤더가 필요하다. 번역은 BFF 한 곳에만 존재하고, 코어는 S6의 이벤트 타입 SSE를 그대로 유지한다.
- next-auth(Keycloak 프로바이더): 로그인→콜백→토큰. 세션 쿠키 → `Authorization` 헤더.
- BFF(`/api/chat/stream`)만 호출. LiteLLM Gateway에 직접 접근 금지.

### 02 · EDGE (Spring Security)
- 필터체인 실행 순서: **CORS → JWT → RBAC → RateLimit**.
- 리액티브 스택이므로 서블릿 필터가 아니라 `WebFilter`를 쓴다. 보안 설정은 `ServerHttpSecurity`로 만든 `SecurityWebFilterChain` 빈이며, 위 순서는 `SecurityWebFiltersOrder`의 위치(CORS → AUTHENTICATION → AUTHORIZATION)에 대응한다. (`OncePerRequestFilter`는 서블릿 전용이라 WebFlux에서 쓸 수 없다.)
- JWT 검증(`oauth2ResourceServer().jwt()`) → `SecurityContext`. 확정된 접근태그는 `Authentication`에 실어 Reactor `Context`를 타고 스트리밍 `Flux` 연산자들을 가로질러 전파된다.
- **RBAC 단계 = 앞단 게이트**: 역할→접근태그 매핑을 호출해 태그 집합을 요청 컨텍스트에 적재. (핵심: 권한 확정을 여기서 끝낸다)
- RateLimit: v0는 자리만(통과/느슨한 기본값).
- Keycloak OIDC Authorization Code Flow, Realm Role → GrantedAuthority.

### 03 · CORE (WebFlux + Spring AI)
- `ChatController`(WebFlux): `/api/chat/stream` → `Flux<ServerSentEvent<…>>`.
- `ChatClient.prompt().stream()`. RAG Advisor 체인에 강결합하지 않는다(E13).
- **검색은 Advisor 안이 아니라 CHAT이 명시적으로 호출한다.** CHAT이 SEARCH를 먼저 부르고(접근태그 필터는 SEARCH의 ES 쿼리에서 적용), 받은 조각을 프롬프트에 주입한 뒤 `ChatClient`를 호출한다. 이유: S6가 요구하는 "sources는 서버 검색 결과에서만 나온다"를 코드 구조로 드러내고, 첫 `text` 토큰보다 먼저 `sources` 이벤트를 확정 발행하기 위해서다. (`QuestionAnswerAdvisor`와 `RetrievalAugmentationAdvisor`는 각자 독립적으로 검색을 수행하는 대안 관계이며 직렬로 엮으면 검색이 두 번 돈다. 스트리밍에서 검색 결과가 첫 토큰보다 먼저 방출된다는 보장도 문서화되어 있지 않다.)
- 검색 쿼리 생성은 독립 단계(향후 쿼리 재작성 삽입 가능).
- 감사 로그: 요청 단위 추적 ID.

### 04 · DATA · RAG
- **PostgreSQL**: 사용자·세션·이력·감사(JPA/Hibernate, Flyway). 블로킹 접근은 전용 계층에 격리.
- **Elasticsearch**: 조각(dense_vector cosine + BM25 하이브리드, nori 분석기), 원문 텍스트, 메타데이터.
- **Spring AI ETL**: Tika/hwplib 추출 → `ChunkingStrategy`(토큰 크기 기준, **글자 경계 안전**) → EmbeddingModel(게이트웨이 경유, 모델 고정).
  - Spring AI의 `TokenTextSplitter`는 쓰지 않는다. 바이트 수준 BPE 토큰 목록을 잘라 디코딩하므로, 한 글자가 여러 토큰에 걸치는 한국어에서 조각 경계가 글자 중간을 잘라 U+FFFD 치환 문자를 남긴다. 토큰은 크기를 재는 데만 쓰고(`TokenCountEstimator`), 자르는 위치는 문장→단어→코드포인트 순으로 찾는다.
- 원본 파일: 로컬 파일시스템 볼륨(저장 백엔드 교체 가능).

### 05 · INFRA (LiteLLM)
- 내부망 전용. 모델별 라우팅·페일오버는 게이트웨이 책임. 코어는 모델명 파라미터만 전달.
- Ollama(온프레) + 클라우드(OpenAI/Anthropic/Google). 임베딩도 동일 게이트웨이 경유.

## 모듈 경계 & 의존 방향

```
                 ┌─────────────────────────────────────┐
   관리자 ──────▶│ IDX (색인)                          │──▶ ES(조각) + FS(원본) + PG(document)
                 └─────────────────────────────────────┘

   사용자 ──▶ AUTH(게이트: 태그확정) ──▶ CHAT ──▶ SEARCH(태그필터+검색) ──▶ sources
                                          │                                    │
                                          ▼                                    ▼
                                     이벤트 SSE 응답 ◀──────────── ChatClient(LLM)
                                          │
                                          ▼ (비동기, 격리 스케줄러)
                                     이력 저장(PG) + AUDIT 기록(PG)
```

**규칙:**
- AUTH가 확정한 접근태그를 CHAT/SEARCH가 **소비만** 한다(재판단 금지).
- SEARCH는 AUTH 태그가 요청 컨텍스트에 있다고 신뢰한다(게이트를 이미 통과했으므로).
- AUDIT는 CHAT 응답 완료와 **독립**(비동기, 실패해도 응답 롤백 안 함).
- 모듈 간 통신은 인터페이스(계약)로만. 구체 구현에 의존 금지.

## 핵심 인터페이스 계약 (경계)

이 인터페이스들은 교체 가능해야 한다(상세는 `docs/02-decisions.md` 경계 참조):
- `DocumentParser`(포맷별 어댑터: Tika/hwplib) — E8
- `ChunkingStrategy`(토큰 청킹, 교체 가능) — E11
- `EmbeddingClient`(게이트웨이 경유, 모델 고정) — E9
- `SearchAnalyzer`(언어별, v0=nori) — E16
- `ResultMerger`(BM25·벡터 병합) — E10
- `RoleTagMapper`(역할→태그, 단일 지점) — E4
- `ContextAssembler`(N턴 조립, 교체 가능) — E2
- `FileStorage`(원본 저장 백엔드) — E14
- `ChatClient` 호출은 RAG 체인에 비강결합(RAG 없는 호출 가능) — E13
