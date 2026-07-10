# LEARNINGS — TDD·구현 교훈 로그
> 형식은 docs/06-memory-protocol.md 참조. append-only.
> 세션 시작 시 이 파일을 먼저 읽는다.

## [2026-07-10] INFRA — `web-application-type: reactive`만으로는 Netty가 보장되지 않는다
- **상황:** WebFlux 스캐폴딩에서 "서블릿 스타터가 섞이면 MVC로 자동설정된다"는 함정을 테스트로 막으려 함.
- **발견:** 실제로 함정을 밟아 확인했다. `spring-boot-starter-web`을 추가하면 두 갈래로 갈린다. (1) `spring.main.web-application-type=reactive`가 **없으면** 컨텍스트 자체가 서블릿이 된다. (2) 설정이 **있어도** 컨텍스트는 리액티브로 유지되지만 웹서버가 Netty가 아니라 **Tomcat**(reactive)으로 바뀐다. 즉 설정만으로는 방어되지 않는다.
- **결정/해결:** `ReactiveStackTest`가 컨텍스트 타입(`ReactiveWebServerApplicationContext`)과 서버 타입(`NettyWebServer`)을 **각각** 단언한다. 두 단언이 서로 다른 실패 양상을 잡는다.
- **영향:** S13, PERF-1
- **다음 주의:** `spring-boot-starter-web`을 절대 추가하지 말 것. 다른 스타터가 전이 의존으로 끌어올 수 있으니, 의존성 추가 후에는 이 테스트를 반드시 돌린다.

## [2026-07-10] INFRA — `analysis-nori`는 공식 ES 이미지에 없다
- **상황:** docker-compose에 ES를 올리며 nori 분석기를 쓰려 함(S15).
- **발견:** `docker.elastic.co/elasticsearch/elasticsearch` 이미지에 nori가 포함되어 있지 않다. `bin/elasticsearch-plugin install --batch analysis-nori`로 직접 설치해야 하며, 플러그인 버전은 ES 버전에 고정된다. 커스텀 이미지를 빌드해 `elasticsearch-plugin list`로 설치를 확인했다.
- **결정/해결:** `docker/elasticsearch/Dockerfile`로 굽고 compose가 build한다. Testcontainers 통합 테스트도 같은 이미지를 써야 색인·검색이 같은 분석기를 쓴다는 보장이 생긴다.
- **영향:** S15, E16, REL-5
- **다음 주의:** ES 버전을 올릴 때 Dockerfile의 태그도 함께 올린다. 폐쇄망 고객은 `artifacts.elastic.co`에서 versioned zip을 받아 오프라인 설치한다.

## [2026-07-10] SEARCH — RRF 라이선스 티어를 신뢰할 수 없다. 선형결합으로 간다
- **상황:** S11(BM25+벡터 하이브리드)을 PERF-3(단일 ES 쿼리)로 구현할 방법을 찾음.
- **발견:** Elastic의 현재 Subscriptions 표는 RRF를 무료 Basic 티어로 표시하지만, Elastic 자신의 Search Labs 블로그는 "RRF는 Enterprise 라이선스 필요"라 적고 있고 커뮤니티에는 Basic에서 `current license is non-compliant for [Reciprocal Rank Fusion (RRF)]` 오류 사례가 있다. **유료→무료 전환 버전은 확인되지 않았다(추정 불가).** 회사별 설치형이라 고객 ES 버전을 우리가 통제하지 못한다.
- **결정/해결:** v0 하이브리드는 최상위 `knn` 절 + `query` 절을 한 요청에 넣는다. ES가 두 점수를 **합산**하며 각 절의 `boost`로 가중한다. 무료 티어에서 확실히 동작하고 PERF-3를 지킨다. RRF는 나중에 `ResultMerger` 뒤에서 교체한다(E10, 색인 구조 불변).
- **영향:** S11, E10, PERF-3
- **다음 주의:** 병합이 ES 안에서 일어나므로 `ResultMerger`는 그 위의 얇은 어댑터다. BM25 점수와 벡터 점수는 스케일이 달라 boost 튜닝이 필요하다.

## [2026-07-10] SEARCH — `knn` 절의 필터는 pre-filter, `bool` 트리의 필터는 kNN에 post-filter다
- **상황:** 접근태그 필터(S3·S4)를 하이브리드 쿼리에 걸 위치를 정함.
- **발견:** `knn` 절은 자체 `filter` 파라미터를 가지며 이것은 후보군 진입 **전에** 걸리는 pre-filter다. 반면 Query DSL 트리 다른 곳의 필터는 kNN에 대해 **post-filter**로 적용된다. 후자만 쓰면 권한 없는 조각이 후보 풀에 들어왔다가 나중에 제거되고, 결과가 `k`보다 적어질 수 있다.
- **결정/해결:** 같은 `terms` 필터를 `knn.filter`와 `bool.filter` **양쪽에** 건다.
- **영향:** SEC-2, S3, S4
- **다음 주의:** SEC-2는 "응답에서 제거"가 아니라 "검색 단계에서 배제"를 요구한다. post-filter만으로는 문자 그대로 이 요구를 어긴다. 이 동작을 Testcontainers 통합 테스트로 못박을 것.

## [2026-07-10] CORE — Spring AI 2.0은 Spring Boot 4를 요구한다. 1.1.x를 쓴다
- **상황:** Spring AI 버전 선택.
- **발견:** Spring AI 최신 GA는 2.0.0이지만 Spring Boot 4.0/4.1 + Framework 7.0을 요구한다(Jackson 3, Jakarta EE 11 베이스라인 변경 동반). Boot 3.x에 남으려면 1.1.x 라인. Maven Central에서 `spring-ai-bom:1.1.8`, `spring-ai-tika-document-reader:1.1.8`, `spring-ai-starter-model-openai:1.1.8` 실재를 직접 확인했다.
- **발견 2:** `QuestionAnswerAdvisor`(`...advisor.vectorstore` 패키지)와 `RetrievalAugmentationAdvisor`(`...rag.advisor`)는 **각자 독립적으로 검색을 수행하는 대안 관계**다. 한 체인에 둘을 넣으면 검색이 두 번 돌고 컨텍스트가 두 번 주입된다. `FilterExpression`은 Advisor가 아니라 검색 요청에 실리는 필터다.
- **발견 3:** Spring AI의 `ElasticsearchVectorStore`는 kNN 전용이며 BM25와의 하이브리드를 지원하지 않는다. `VectorStore` 추상화 전체가 `similaritySearch()` = kNN이다.
- **결정/해결:** Spring AI 1.1.8 핀. 검색은 `VectorStore`를 쓰지 않고 ES Java 클라이언트(9.4.3)로 직접 쿼리한다. 조각의 메타 7종과 `access_tags`를 정확히 통제할 수 있어 IDX에도 유리하다.
- **영향:** S8-1, S11, E7, E10, docs/01
- **다음 주의:** Boot 4로 올릴 때 Spring AI 2.0을 함께 올린다. 오래된 튜토리얼은 `QuestionAnswerAdvisor`의 옛 패키지(`.vectorstore` 없는)를 쓰므로 import가 틀린다.

## [2026-07-10] CHAT — 스트리밍에서 검색 결과가 첫 토큰보다 먼저 온다는 보장은 없다
- **상황:** S6("sources는 서버 검색 결과에서만")를 Spring AI Advisor로 구현할 수 있는지 검토.
- **발견:** `.stream().chatClientResponse()`로 `ChatClientResponse.context()`에서 `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`를 꺼낼 수 있는 것은 사실이다. 그러나 **검색 결과가 첫 `text` 토큰보다 먼저 방출된다는 보장은 문서화되어 있지 않다.** 구조상 검색은 모델 호출 전에 끝나지만, 공개 API는 별도의 "문서 준비됨" 신호를 주지 않고 Flux의 각 요소에 context를 붙일 뿐이다.
- **결정/해결:** CHAT이 SEARCH를 **명시적으로 먼저 호출**하고, 받은 조각을 프롬프트에 주입한 뒤 `ChatClient`를 호출한다. `sources` 이벤트를 첫 토큰 앞에 확정 발행할 수 있고, SEARCH가 진짜 독립 모듈이 되며 E13(RAG 비강결합)·E3와도 맞는다.
- **영향:** S6, E3, E13, docs/01
- **다음 주의:** 프레임워크 내부 방출 순서에 근거의 존재를 걸지 말 것. S6는 근거의 **원천**을 요구하므로 그 사실이 코드 구조에 드러나야 한다.

## [2026-07-10] CLIENT — `useChat`은 SSE의 `event:` 이름을 읽지 않는다
- **상황:** S6의 이벤트 타입 SSE(`text`/`sources`/`error`/`done`)를 Vercel AI SDK `useChat`이 그대로 소비할 수 있는지 확인.
- **발견:** `useChat`(현재 `@ai-sdk/react`, `ai/react`는 제거됨)은 SSE의 `event:` 필드가 아니라 `data:` 안 JSON의 `type` 필드로 파트를 구분한다. 응답에 `x-vercel-ai-ui-message-stream: v1` 헤더가 필요하고, 텍스트는 `text-start`/`text-delta`/`text-end`로 흐르며 `data: [DONE]`으로 끝난다. 근거용으로 `source-url`·`source-document` 내장 파트가 있으나 `location`·점수 같은 필드를 담기엔 좁아 커스텀 `data-sources` 파트가 적합하다.
- **결정/해결:** 코어는 S6의 명명된 이벤트를 그대로 내보내고, **BFF(`/api/chat/stream`)가 AI SDK 파트로 번역**한다. 번역이 한 곳에만 존재한다.
- **영향:** S6, docs/01
- **다음 주의:** 프론트 스캐폴딩은 CHAT 모듈 착수 시점에 한다. AI SDK는 v5에서 transport 아키텍처로 바뀌어(`append`→`sendMessage`, `isLoading`→`status`) 옛 예제가 대부분 맞지 않는다.

## [2026-07-10] PROCESS — 규칙을 실행 가능하게 만들자 문서 안의 모순이 드러났다 (OQ-003)
- **상황:** `docs/07`은 "테스트만 먼저 커밋해도 좋다(red 커밋)"고 하면서 동시에 "`main`은 항상 테스트 통과 상태 유지", "green 아닌 상태를 임시로 main에 병합 금지"라고 적고 있었다. 게다가 `Tests: N passed` 트레일러는 red 커밋에 붙일 수 없다.
- **발견:** 브랜치를 쓰면 세 규칙이 다 성립하지만, 우리는 줄곧 `main`에서 직접 작업했다. **모순은 문서를 읽을 때가 아니라 훅을 만들 때 드러났다.** 훅은 red 커밋을 막으며 조용히 한쪽 편을 들고 있었고, 아무도 그 사실을 몰랐다.
- **결정/해결:** red 허용 문구를 삭제하고 Red+Green 묶음 커밋을 정본으로 했다. 잃는 증거(테스트가 실제로 실패했다는 사실)는 커밋 본문의 `Red 확인:` 기록으로 보존한다. 훅은 동작을 바꾸지 않았다 — 탈출구를 늘리지 않는 것이 이 선택의 이점이다.
- **영향:** docs/07, docs/08 D-5, OQ-003
- **다음 주의:** **규칙을 코드로 옮기면 모순이 드러난다.** 문서끼리 충돌할 때 강제 장치가 어느 쪽을 택하고 있는지 확인하라 — 그 선택은 이미 내려져 있고, 다만 기록되지 않았을 뿐이다.

## [2026-07-10] SEC — `.gitignore`의 선행 슬래시가 업로드 원본을 놓쳤다
- **상황:** OQ-003 검증 중 `git status`에 `backend/data/documents/` 파일들이 보였다.
- **발견:** `.gitignore`의 `/data/`는 **선행 슬래시 때문에 저장소 루트만** 잡는다. 그런데 테스트는 작업 디렉토리가 `backend/`라서 `backend/data/documents` 아래에 원본을 쓴다. e2e 테스트가 업로드한 파일 27개가 `git add -A backend`에 딸려 들어가 여러 커밋 전부터 추적되고 있었다.
- **결정/해결:** `data/`로 바꿔 어느 깊이에서든 잡는다. 추적 중이던 27개는 `git rm --cached`로 뺐다. 내용이 테스트 픽스처 텍스트뿐이고 무해해서 히스토리는 재작성하지 않았다(히스토리 재작성은 되돌릴 수 없다).
- **영향:** SEC-3, S16
- **다음 주의:** 문서 저장 루트가 저장소 안에 있으면 언젠가 새어 나간다. `.gitignore` 패턴은 **작업 디렉토리가 바뀌는 경우**(테스트가 `backend/`에서 도는 것처럼)를 상상해서 쓴다. 그리고 `git add -A <디렉토리>`는 무시되지 않는 산출물을 조용히 삼킨다.

## [2026-07-10] CORE — 부하 테스트가 `ensureExists`의 경합 버그를 잡았다
- **상황:** "부하 시에도 블로킹 접근이 논블로킹 흐름을 막지 않는다"(docs/00)를 검증하려고 동시 요청 32개를 던짐.
- **발견 1 (진짜 버그):** `AppUserRepository.ensureExists`가 "찾고 없으면 삽입"이었다. 같은 사용자의 첫 요청이 동시에 들어오면 **전부 "없다"고 판단하고 전부 삽입**해 unique 제약을 위반하고 500이 났다. 단일 요청 테스트로는 절대 드러나지 않는다. `insert ... on conflict (keycloak_subject) do nothing` 후 읽기로 고쳤다 — 애플리케이션에서 락을 잡는 대신 DB의 제약을 그대로 쓴다.
- **발견 2:** BlockHound는 **모든 `park`를 블로킹으로 본다.** Jackson의 `DeserializerCache`가 타입별 역직렬화기를 처음 만들 때 `ReentrantLock`을 잡는데, 동시 요청이 몰리면 여기서 걸려 요청이 500이 된다. I/O가 아니라 일회성 워밍업 락이다. `BlockHoundIntegration`을 `META-INF/services`로 등록해 **그 지점만 좁게 허용**했다. ServiceLoader로 등록하면 `BlockHound.install()`이 어디서 불리든 적용되어 테스트 순서에 의존하지 않는다.
- **발견 3:** 워밍업을 분리하지 않으면 무엇을 재는지 알 수 없다. 워밍업 전 2014ms, 후 **246ms**(직렬 하한 6400ms 대비 26배 겹침). 첫 측정의 88%가 JIT·Jackson 캐시·커넥션 풀·Hibernate 초기화였다.
- **결정/해결:** `LoadIsolationTest`가 스위트의 일부다. 블로킹 검색을 200ms로 만들고 32개를 동시에 던져, 직렬이면 6400ms가 걸릴 일을 246ms에 끝내는지 본다. 측정값을 로그로 남겨 여유가 얼마나 남았는지 보이게 했다.
- **영향:** docs/00 체크리스트, S13, PERF-1, OQ-005
- **다음 주의:** "격리되어 있다"(스레드 이름 단언)와 "부하에서 견딘다"(오버랩 측정)는 다른 주장이다. 그리고 동시성 버그는 부하를 주기 전까지 존재하지 않는 것처럼 보인다.

## [2026-07-10] INFRA — 로컬 PostgreSQL이 5432를 가로챈다 (compose 포트 충돌, 오진하기 쉬움)
- **상황:** compose 전체 기동 후 코어를 붙였더니 Flyway가 `password authentication failed for user "llmhub"`로 죽었다.
- **발견:** 로컬에 PostgreSQL 18 서비스가 설치되어 있었고 `0.0.0.0:5432`를 잡고 있었다. Docker는 `[::]:5432`(IPv6)만 잡는다. 그래서 호스트에서 `localhost:5432`로 나가는 연결이 **컨테이너가 아니라 로컬 서버로 갔다.** 그쪽엔 그 비밀번호의 `llmhub` 사용자가 없다.
- **오진하기 쉬운 이유:** `docker compose exec postgres psql -U llmhub`는 잘 된다. 컨테이너 내부는 trust 인증이라 **틀린 비밀번호로도 통과한다.** "DB는 멀쩡한데 앱만 못 붙는다"로 보여 비밀번호·CRLF·Gradle 데몬 환경변수를 먼저 의심하게 된다.
- **결정/해결:** `.env.example`의 기본 `POSTGRES_PORT`를 **5433**으로 바꿨다. compose 매핑과 Spring datasource URL이 같은 변수를 쓰므로 한 곳만 고치면 된다.
- **진단법:** `netstat -ano | grep :5432`로 리스너가 둘인지 본다. 호스트 경로를 그대로 시험하려면 `docker run --rm postgres:17-alpine psql "postgresql://user:pw@host.docker.internal:PORT/db"`.
- **영향:** REL-5, OQ-002
- **다음 주의:** "컨테이너 안에서는 되는데 호스트에서는 안 된다"면 포트 충돌을 먼저 의심한다. 그리고 컨테이너 내부 접속으로 비밀번호를 검증하지 말 것 — trust 인증이라 아무 비밀번호나 통과한다.

## [2026-07-10] TOOLING — 셸에 마크다운 코드 펜스를 넣지 말 것 (의도치 않은 명령 실행)
- **상황:** `node -e "..."` 안에서 README의 ```` ```bash ```` 블록을 치환하려 함. 셸 문자열 안에 백틱이 들어갔다.
- **발견:** bash가 백틱을 **명령 치환**으로 해석해 README 코드 블록의 내용을 그대로 실행했다. `git config ...`, `cp .env.example .env`, **`docker compose up -d`** 가 실제로 돌아 컨테이너 넷이 떴다. 파괴적인 작업은 없었지만 의도하지 않은 상태 변경이다.
- **결정/해결:** 문서 파일 수정은 셸을 거치지 않고 편집 도구로 한다. 셸에서 백틱이 든 문자열을 다루지 않는다. 여러 줄 문자열이 필요하면 heredoc(`<<'EOF'`)을 쓰되, 그 안에도 백틱을 넣지 않는다.
- **영향:** 없음(되돌릴 수 있는 상태 변경). 다만 `.env`가 placeholder 값으로 생성되었다(gitignore 대상).
- **다음 주의:** 셸 명령을 조립할 때 "이 문자열이 셸에 의해 해석될 여지가 있는가"를 먼저 묻는다. 백틱·`$(...)`·`$VAR`는 인용 안에서도 살아난다. 부작용이 있는 명령이 문서 안에 적혀 있다면 더더욱.

## [2026-07-10] CLIENT — `useChat`은 `body`를 받지 않는다. transport가 요청을 만든다
- **상황:** BFF를 만들고 `useChat`으로 `sessionId`를 함께 보내려 함.
- **발견:** AI SDK v5부터 `useChat({ body })`는 없다. `new DefaultChatTransport({ api, body })`를 만들어 `useChat({ transport })`로 넘긴다. **타입 검사가 이 추측을 잡았다.** LEARNINGS에 "v5에서 transport 아키텍처로 바뀌었다"고 적어뒀는데도 코드에서는 옛 API를 썼다 — 기록만으로는 부족하고 타입이 막아야 한다.
- **발견 2:** Node 22의 `--experimental-strip-types`는 **생성자 파라미터 프로퍼티**(`constructor(private readonly x: T)`)를 지원하지 않는다. 타입을 지우기만 하고 코드를 생성하지 않기 때문이다. `ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX`. 명시적 필드 대입으로 쓴다.
- **결정/해결:** BFF 번역기를 순수 모듈로 분리해 Node 내장 `node:test`로 검증한다. vitest·jest를 의존성에 추가하지 않았다. 파트 형식(`text-start`/`text-delta`/`text-end`/`start`/`finish`/`error`/`data-*`)과 헤더(`x-vercel-ai-ui-message-stream: v1`)는 설치된 `ai@7.0.19`의 타입 정의에서 직접 확인했다.
- **영향:** S6, docs/01, REQ-CHAT
- **다음 주의:** SSE 청크 경계는 프레임 중간을 자른다. 버퍼에 남긴 마지막 조각을 다음 청크와 이어붙이지 않으면 토큰이 통째로 사라진다. 이 케이스를 테스트로 재현해 뒀다.

## [2026-07-10] CHAT — Spring AI openai 스타터는 오디오·이미지 모델까지 자동설정한다
- **상황:** `ChatClient`를 쓰려고 `spring-ai-starter-model-openai`를 추가.
- **발견:** 스타터가 chat뿐 아니라 **audio speech·transcription·image·moderation 모델까지 자동설정**하고, 각각 `spring.ai.openai.api-key`를 요구한다. 키가 없으면 `openAiAudioSpeechModel` 빈 생성에서 터져 **애플리케이션 컨텍스트가 아예 뜨지 않는다.** 웹 테스트 18개가 한꺼번에 죽었다.
- **결정/해결:** `spring.ai.openai.base-url`(LiteLLM)과 `api-key`를 설정했다. 코어는 모델명만 전달하고 라우팅은 게이트웨이 책임이다(S8-1, E7).
- **발견 2:** `ChatModel`은 `call(Prompt)`과 `stream(Prompt)`을 모두 가져 **함수형 인터페이스가 아니다.** 테스트 대역은 람다가 아니라 클래스로 만들어야 한다.
- **영향:** S8-1, E7, E13, REQ-CHAT
- **다음 주의:** Spring AI 스타터를 추가할 때마다 전체 스위트를 돌려 컨텍스트 기동을 확인한다. `issuer-uri`와 같은 부류의 함정이다 — 쓰지도 않는 빈이 기동을 막는다.

## [2026-07-10] AUTH/CORE — `issuer-uri`는 기동을 막고, BlockHound는 파일 I/O를 잡는다
- **상황:** Keycloak 리소스 서버 설정과 블로킹 격리(S13)를 처음 구현.
- **발견 1:** `spring.security.oauth2.resourceserver.jwt.issuer-uri`를 쓰면 **빈 생성 시점에 Keycloak으로 네트워크 호출**이 나간다. Keycloak이 떠 있지 않으면 애플리케이션 컨텍스트가 아예 뜨지 않아 모든 `@SpringBootTest`가 죽는다. `jwk-set-uri`는 첫 검증 때 지연 조회하므로 컨텍스트가 뜬다.
- **발견 2:** JPA·Flyway가 클래스패스에 들어오면 **모든** `@SpringBootTest`가 기동 시 DB에 연결한다. 웹 테스트도 예외가 아니다. Testcontainers PostgreSQL을 `ApplicationContextInitializer`로 붙여 해결했다(`spring-boot-testcontainers`의 `@ServiceConnection`은 사전 승인 목록 밖이라 쓰지 않았다).
- **발견 3:** BlockHound는 JDK 21에서 **`FileInputStream` 읽기를 실제로 잡는다**(직접 실험으로 확인). `Thread.sleep` 계열은 못 잡는다. 그래서 "BlockHound가 무장되어 있는지"를 파일 읽기로 단언하는 테스트를 따로 뒀다. JVM 인자 `-XX:+AllowRedefinitionToAddDeleteMethods`가 없으면 아예 동작하지 않는다.
- **결정/해결:** `Blocking.call/run`이 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`으로 격리한다. 실행 스레드 이름이 `boundedElastic-`으로 시작하는지 단언하는 테스트를 BlockHound와 **함께** 둔다.
- **영향:** S13, S25, PERF-1, E12, REL-5
- **다음 주의:** `publishOn`은 하위 연산자만 옮겨 블로킹 소스가 이벤트 루프에 남는다. 반드시 `subscribeOn`. 그리고 안전장치는 위반을 주입해 검증한다 — "통과했다"가 "위반이 없다"를 뜻하려면 "위반하면 실패한다"가 먼저 참이어야 한다.

## [2026-07-10] IDX — 바이너리 픽스처는 코드로 만든다 (PDF·hwp)
- **상황:** "PDF 업로드", "hwp 업로드" 시나리오를 검증하려면 실제 바이너리가 필요했다.
- **발견:** 저장소에 바이너리를 커밋하거나 PDF 생성 라이브러리를 의존성에 추가하지 않고도 된다. PDF는 규격대로 객체·xref·trailer를 조립하면 Tika(PDFBox)가 읽는다(`MinimalPdf`). hwp는 `BlankFileMaker.make()` → `paragraph.getText().addString(...)` → `HWPWriter.toStream(...)`으로 만든다(`MinimalHwp`). 읽기는 `HWPReader.fromInputStream` + `TextExtractor.extract(hwp, TextExtractMethod.OnlyMainParagraph)`.
- **결정/해결:** 픽스처 생성기를 테스트 코드에 둔다. 내용이 코드로 드러나 리뷰 가능하고 의존성이 늘지 않는다. PDF 본문은 ASCII만 담는다 — 한국어를 넣으려면 폰트 임베딩이 필요한데, 이 테스트가 확인하려는 것은 추출 경로이지 폰트가 아니다.
- **한계(정직하게):** hwp 픽스처를 hwplib으로 만들고 hwplib으로 읽으므로 **어댑터 배선**을 검증하지, 한글 워드프로세서가 실제로 저장한 파일과의 호환성은 검증하지 않는다. 후자는 진짜 샘플이 필요하다.
- **영향:** REQ-IDX, S8-2, E8
- **다음 주의:** Testcontainers 컨테이너는 JVM당 하나로 모은다(정적 싱글턴). 클래스마다 띄우면 통합 테스트가 몇 배 느려지고, 훅이 매 커밋마다 전체 스위트를 돌리므로 그 비용이 곧바로 개발 속도에 꽂힌다.

## [2026-07-10] TEST — ArchUnit의 `..search..`는 서드파티 패키지까지 매치한다 (경계 규칙 오탐)
- **상황:** ES Java 클라이언트를 IDX에 도입하자 `IDX는_다른_모듈에_의존하지_않는다` 규칙이 갑자기 실패했다.
- **발견:** ArchUnit의 `..search..` 패턴은 "이름에 `search` 세그먼트가 들어간 **모든** 패키지"를 매치한다. `co.elastic.clients.elasticsearch.core.search`가 걸렸다. 우리 모듈 경계가 아니라 라이브러리 내부 패키지를 잡은 **오탐**이다. `..chat..`, `..idx..` 등도 같은 위험이 있다.
- **결정/해결:** 모든 규칙을 `com.llmhub.search..`처럼 **완전한 패키지 접두사**로 좁혔다. 규칙을 약화시킨 것이 아니라 의도한 규칙으로 바로잡은 것이다.
- **영향:** MAINT-1, `ModuleBoundaryTest`
- **다음 주의:** ArchUnit에서 `..x..`는 편해 보이지만 서드파티와 충돌한다. 경계 규칙은 항상 루트 패키지부터 적는다. 또한 Gradle의 `--tests` 필터는 ArchUnit 테스트를 걸러내지 못해 항상 함께 실행된다.

## [2026-07-10] IDX — ES 9.x Java 클라이언트는 rest5 트랜스포트를 쓴다 / nori 동작을 실제로 확인함
- **상황:** 조각 저장소를 ES Java 클라이언트 9.4.3으로 구현하고 Testcontainers로 검증하려 함.
- **발견:**
  - 9.x의 필수 의존은 `elasticsearch-rest5-client`다. 구 `elasticsearch-rest-client`는 compile 의존이 아니다. 진입점은 `Rest5Client.builder(URI...)` → `new Rest5ClientTransport(lowLevel, new JacksonJsonpMapper())`.
  - `dense_vector`의 `similarity`는 문자열이 아니라 `DenseVectorSimilarity` enum이다.
  - Testcontainers는 compose와 **같은 Dockerfile**을 `ImageFromDockerfile().withDockerfile(path)`로 빌드할 수 있다. ES 8+ 이미지는 보안이 기본 활성이므로 `withEnv("xpack.security.enabled","false")`가 필요하다.
  - **nori가 실제로 동작함을 확인했다.** `_analyze`로 `chunk_text` 필드에 "연차휴가는"을 넣으면 `연차`·`휴가` 토큰이 나온다. nori가 없으면 통째로 한 토큰이 되어 "연차휴가"로 검색되지 않는다.
- **결정/해결:** `ElasticsearchChunkRepository`가 `chunk_text`(nori)와 `embedding`(dense_vector, cosine)을 한 문서에 담는다. 두 필드가 함께 있어야 BM25와 벡터를 단일 쿼리로 결합할 수 있다(S11, PERF-3). ES 문서 `_id`는 `documentId:runId:location`으로 결정적으로 만든다 — 재실행해도 중복이 안 생기고, 신·구 버전 조각이 서로를 덮어쓰지 않는다(S17).
- **영향:** S7, S11, S15, S17, PERF-3, REL-5
- **다음 주의:** 통합 테스트가 compose와 다른 이미지를 쓰면 "색인과 검색이 같은 분석기를 쓴다"는 보장이 사라진다. 반드시 같은 Dockerfile을 쓸 것.

## [2026-07-10] IDX — Spring AI `TokenTextSplitter`는 한국어 글자를 조각 경계에서 깨뜨린다
- **상황:** S12(토큰 크기 기준 기계적 청킹)를 `docs/01`이 지정한 `TokenTextSplitter`로 구현하려 함.
- **발견:** 이 구현은 텍스트를 **바이트 수준 BPE 토큰**으로 인코딩한 뒤 토큰 목록을 잘라 각 조각을 디코딩한다. 한글 한 글자는 여러 토큰에 걸치므로 조각 경계가 글자 중간을 자르고, 디코딩 결과에 **U+FFFD 치환 문자**가 남는다. 실제 관측: `[연차휴가는 근로기준법에 �]` / `[�라 부여한다…]` — "따"가 두 동강 났다. **예외는 전혀 발생하지 않는다.** 영어에서는 거의 드러나지 않고 한국어에서만 터진다.
- **결정/해결:** `TokenTextSplitter`를 버리고 청킹을 직접 구현했다. 토큰은 **크기를 재는 데만** 쓰고(`org.springframework.ai.tokenizer.TokenCountEstimator`, spring-ai-commons에 있음), 자르는 위치는 문장 → 단어 → 코드포인트 순으로 내려가며 찾는다. 글자 안쪽을 자르지 않으므로 손상이 구조적으로 불가능하다. 손상 여부를 단언하는 별도 불변식 테스트(`TokenChunkingCharacterIntegrityTest`)를 두었다.
- **영향:** S12, S15, E11, REL-2, `docs/01`(문구 정정), `CLAUDE.md`(스택 표 정정)
- **다음 주의:** **토큰 기반 도구는 조용히 한국어를 손상시킬 수 있다.** 임베딩 입력 절단, 컨텍스트 윈도우 절단, 프롬프트 트렁케이션 등 토큰 단위로 텍스트를 자르는 모든 지점에서 같은 함정을 의심할 것. 손상은 예외가 아니라 치환 문자로 나타나므로 테스트로만 잡힌다.
- **참고:** `spring-ai-core`는 없어졌다. 1.1.x에서 `TokenTextSplitter`·`Document`·`TokenCountEstimator`는 모두 `spring-ai-commons`에 있다.

## [2026-07-10] INFRA — `grep -v '^\+\+\+'`는 diff의 모든 추가 줄을 지운다 (안전장치가 조용히 무력화됨)
- **상황:** pre-commit 훅에서 diff의 추가 줄(`+`)만 골라 금지 토큰(`@Disabled` 등)을 찾으려 함. 헤더 `+++ b/path`를 제외하려고 `grep -E '^\+' | grep -v '^\+\+\+'`를 씀.
- **발견:** GNU **BRE에서 `\+`는 리터럴 `+`가 아니라 "1회 이상" 수량자**다. 그래서 `^\+\+\+`가 헤더뿐 아니라 `+`로 시작하는 **모든 줄**에 매치되어 검사할 내용이 하나도 남지 않았다. 훅은 조용히 통과했고, `@Disabled`가 붙은 테스트 파일이 **실제로 커밋되었다**(되돌림). 안전장치가 실패했는데 실패했다는 신호조차 없었다.
- **결정/해결:** diff 파싱은 전부 `grep -E`(ERE)로 통일하고, 헤더는 경로 접두사까지 포함해 정확히 제외한다(`^\+\+\+ b/`, `^--- a/`). 훅 작성 후 **위반을 일부러 저질러 차단되는지 확인**했다. 7가지 시나리오 전부 검증.
- **영향:** docs/08 D-4, `.githooks/pre-commit`
- **다음 주의:** **안전장치는 반드시 위반을 주입해 검증한다.** "훅이 통과했다"는 "위반이 없다"가 아니라 "훅이 아무것도 안 했다"일 수 있다. 통과만 보고 안심한 것이 이번 버그를 놓칠 뻔한 이유다.

## [2026-07-10] TEST — ArchUnit 1.x는 빈 규칙을 실패로 처리한다 / BlockHound는 JVM 플래그가 필요하다
- **상황:** 모듈 경계(MAINT-1)를 Gradle 멀티모듈 대신 ArchUnit 테스트로 강제하려 함.
- **발견 1:** ArchUnit 1.x는 규칙이 검사할 클래스를 하나도 못 찾으면 "failed to check any classes"로 **실패**한다(패키지명 오타 방지 목적). 스캐폴딩 단계에서는 모듈 패키지가 비어 있어 모든 경계 규칙이 빈 규칙이다. `archunit.properties`에 `archRule.failOnEmptyShould = false`로 전역 허용했다.
- **발견 2(미사용, 착수 시 필요):** BlockHound는 JDK 13+에서 `-XX:+AllowRedefinitionToAddDeleteMethods` JVM 플래그가 있어야 동작한다. 게다가 JDK 21에서는 `Thread.sleep` 계열을 더 이상 잡지 못하는 구멍이 있다.
- **결정/해결:** ArchUnit은 빈 규칙 허용. 블로킹 격리 테스트(S13)는 BlockHound에만 의존하지 않고 **실제 실행 스레드 이름이 `boundedElastic-`으로 시작하는지 단언**하는 테스트를 함께 둔다.
- **영향:** MAINT-1, MAINT-2, S13, docs/05
- **다음 주의:** `failOnEmptyShould=false` 때문에 패키지명을 오타내면 규칙이 조용히 통과한다. 모듈에 클래스가 들어오면 규칙이 실제로 동작하기 시작한다. 블로킹 격리는 `subscribeOn(boundedElastic())`이며 `publishOn`이 아니다(`publishOn`은 하위 연산자만 옮겨 블로킹 소스가 이벤트 루프에 남는다).
