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
