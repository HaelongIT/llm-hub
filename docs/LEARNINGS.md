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

## [2026-07-10] INFRA — 운영 compose는 base, 개발은 override (REL-5)
- **상황:** compose 하나로 개발과 운영을 겸하고 있었다. ES 보안 꺼짐, Keycloak dev 모드, 모든 포트 개방. "회사별 설치형과 동일 산출물"(REL-5)이 성립하지 않았다.
- **결정/해결:** `docker-compose.yml`을 **운영 기준**으로 두고, `docker-compose.override.yml`이 개발용 완화를 얹는다. 고객에게 나가는 산출물이 base 그대로다. 코어·BFF는 `profiles: ["app"]`으로 묶어 개발에서는 소스로 돌린다. 운영에서는 `-f docker-compose.yml`을 **명시해야 한다** — override가 저장소에 있으면 `docker compose up`이 조용히 얹어 보안을 완화한다.
- **발견 1 (내 설계 버그):** Keycloak을 내부망에만 두면 **로그인이 불가능하다.** 브라우저가 OIDC 리다이렉트로 직접 접근해야 한다. `docs/01`의 SEC-1은 LiteLLM·ES·PostgreSQL만 내부망 전용으로 지정했고 Keycloak은 목록에 없다 — 문서를 정확히 읽었어야 했다.
- **발견 2:** Keycloak 운영 모드(`start`)는 실제 DB를 요구한다. 내장 H2는 dev 전용이다. 같은 PostgreSQL 인스턴스에 `keycloak` 데이터베이스를 만들어 붙였다(`docker-entrypoint-initdb.d`는 데이터 디렉토리가 비었을 때만 실행된다).
- **검증:** 별도 프로젝트명·별도 `DATA_ROOT`·다른 포트로 운영 스택을 실제로 띄웠다. ES는 자격증명 없이는 401, `elastic`으로는 200. Keycloak은 PostgreSQL에 테이블 100개를 만들었다. 백엔드는 Flyway 마이그레이션 후 Netty로 떴다. **호스트에 바인딩된 포트는 정확히 둘**(frontend, keycloak). 개발 스택은 건드리지 않았다.
- **영향:** REL-5, S26/S27, SEC-1
- **다음 주의:** `docker compose port`는 어느 파일 조합으로 해석했느냐에 따라 다른 답을 준다. 실제 바인딩은 `docker ps`로 본다. 그리고 데이터 볼륨 경로를 변수로 두면(`DATA_ROOT`) 운영 구성을 개발 데이터를 파괴하지 않고 검증할 수 있다.

## [2026-07-10] CLIENT — `typescript@7`은 JS API가 없다. 타입 검사는 통과하고 빌드가 죽는다
- **상황:** npm 레지스트리에서 `typescript`의 `latest`가 `7.0.2`라 그것을 사전 승인 목록에 올렸다. `npx tsc --noEmit`가 통과해 문제를 못 봤다.
- **발견:** TypeScript 7은 **Go 기반 재작성판**이다. `package.json`에 `main` 필드가 없고 `tsc` 바이너리만 제공한다. Next는 `require('typescript')`로 프로그램 API를 쓰므로 `next build`가 `The "id" argument must be of type string. Received undefined`라는 무관해 보이는 메시지로 죽는다. **CLI는 되고 API는 안 되는** 상태라 타입 검사만으로는 절대 드러나지 않는다.
- **결정/해결:** `typescript@5.9.3`으로 고정했다. 그리고 pre-commit 훅에 `next build`를 추가했다 — 깨끗한 빌드가 6초라 비용이 작고, 타입 검사가 못 잡는 부류를 잡는다. 훅이 실제로 빌드 실패를 차단하는지 일부러 깨뜨려 확인했다.
- **영향:** docs/08 §E(사전 승인 의존성), `.githooks/pre-commit`
- **다음 주의:** **`latest` 태그가 곧 "우리가 쓸 수 있는 최신"은 아니다.** 메이저 재작성판이 latest를 차지할 수 있다. 그리고 "타입 검사 통과"는 "빌드 성공"이 아니다 — 검증 수단을 하나만 두면 그 수단이 못 보는 층이 통째로 비어 있게 된다.

## [2026-07-10] INFRA — Keycloak 개발용 계정: 프로필이 비면 로그인이 막힌다
- **상황:** `bootstrap-dev.sh`로 `dev-user`/`dev-admin`을 만들고 토큰을 받으려 함.
- **발견 1:** Keycloak 24+의 **선언적 사용자 프로필**은 `email`·`firstName`·`lastName`을 필수로 본다. 비어 있으면 `VERIFY_PROFILE` 필수 액션이 걸려 로그인이 `invalid_grant: Account is not fully set up`으로 막힌다. **사용자의 `requiredActions`는 빈 배열로 보여** 진단이 어렵다 — 역할·비밀번호·enabled를 아무리 확인해도 원인이 안 나온다. 프로필 세 필드를 채우면 즉시 해결된다.
- **발견 2:** Git Bash(Windows)는 `docker compose exec ... /opt/keycloak/bin/kcadm.sh` 같은 인자를 **Windows 경로로 변환**해 `no such file or directory`를 낸다. `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`로 막는다(다른 OS에서는 무시되는 환경변수다).
- **발견 3:** `curl -F 'file=@/tmp/x.txt'`도 같은 경로 변환에 걸린다. 그리고 Git Bash는 **한글 본문을 UTF-8이 아닌 인코딩으로 보낸다** — LiteLLM 로그에 `UnicodeDecodeError`가 찍히고 요청은 `model=None`으로 해석된다. 셸에서 API를 시험할 때 한글을 넣지 말 것.
- **결정/해결:** 스크립트는 프로필 세 필드를 항상 채운다. 비밀번호·클라이언트 시크릿은 `.env`에서 읽는다(SEC-3). 재실행 가능하다.
- **영향:** S25, SEC-3, REL-5
- **다음 주의:** 실제 Keycloak JWT로 백엔드를 때려본 것은 이번이 처음이다. 지금까지 모든 테스트가 `ReactiveJwtDecoder`를 대역으로 썼기 때문에, **서명 검증과 realm_access.roles 파싱이 진짜로 도는지는 검증된 적이 없었다.** 확인 결과 401/403/200이 전부 기대대로였다.

## [2026-07-10] INFRA — LiteLLM의 "No connected db"는 키가 틀렸다는 뜻이다
- **상황:** 코어가 색인 중 500. 원인은 `HttpClientErrorException$BadRequest: {"error":{"message":"No connected db.","type":"no_db_connection"}}`.
- **발견:** LiteLLM은 마스터 키와 다른 키를 받으면 **가상 키로 간주해 DB를 조회**하고, DB가 없으니 `No connected db`로 답한다. 키 불일치라는 진짜 원인을 전혀 드러내지 않는다. 여기서는 `application.yml`의 기본값 `changeme`와 `.env`의 `change-me`가 달랐다(코어를 `--args` 없이 띄워 환경변수가 안 실렸다).
- **결정/해결:** 키를 맞추면 다음 오류로 넘어간다 — `model "bge-m3" not found, try pulling it first`. 즉 두 오류는 서로 다른 층이다. README에 `ollama pull bge-m3`를 명시했다.
- **영향:** S8-1, E7
- **다음 주의:** `No connected db`를 보면 DB가 아니라 **키**를 의심한다. 그리고 Gradle 데몬은 나중에 export한 환경변수를 물려받지 않는다 — `--no-daemon`으로 띄우거나 `--args`로 직접 넘긴다.

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

## [2026-07-10] OPS — 관리 포트를 분리해도 보안 필터체인은 따라온다 (헬스체크가 401)
- **상황:** REL-5 헬스체크. SEC-1("모든 API는 인증 필요")을 지키면서 오케스트레이터가 자격증명 없이 기동을 확인해야 한다. `management.server.port`로 별도 포트에 두면 액추에이터가 보안 밖에 놓인다고 **가정**했다.
- **발견:** 아니다. WebFlux에서 관리 자식 컨텍스트는 부모의 `WebFilter`(= `WebFilterChainProxy`)를 그대로 쓴다. 실측: 관리 포트 응답에 `WWW-Authenticate: Bearer`와 우리가 붙인 `X-Trace-Id`가 그대로 있었다. 즉 **포트만 나눠서는 헬스가 열리지 않는다.** 서블릿 스택의 `ManagementWebSecurityAutoConfiguration` 동작을 리액티브에 그대로 적용하면 틀린다.
- **결정/해결:** `HIGHEST_PRECEDENCE` 체인을 하나 더 두고 **`securityMatcher`를 "관리 포트 AND /actuator/health"로** 좁혀 `permitAll`했다(`ManagementPortMatcher`). 포트 판정은 `exchange.getRequest().getLocalAddress()`와 `local.management.port`를 비교한다. 경로만으로 열면 애플리케이션 포트의 헬스까지 열려 SEC-1이 깨진다. 관리 포트가 없거나 앱 포트와 같으면 매치하지 않는다(설정 실수로 노출되는 경로 차단).
- **영향:** SEC-1, REL-5, `auth/SecurityConfig`, `docker-compose.yml`
- **다음 주의:** 보안 경계에 대한 프레임워크 동작은 **문서가 아니라 응답으로 확인한다.** 이 가정은 테스트를 먼저 썼기 때문에 잡혔다 — "관리 포트 200, 앱 포트 401"을 둘 다 단언한 덕분이다. 한쪽만 단언했으면 통과했을 것이다. 또한 `SecurityWebFilterChain` 빈이 둘이 되면 `@Autowired SecurityWebFilterChain`이 깨진다(기존 테스트 수정 필요).

## [2026-07-10] OBS — Reactor Context의 추적 ID를 MDC로 옮기는 지점은 블로킹 경계 한 곳이다
- **상황:** REL-3. 모든 요청에 `trace_id`를 발급해 로그·감사에 연결해야 한다. `micrometer-context-propagation` + 전역 훅으로 MDC를 리액티브 전반에 전파하는 방법이 있다.
- **발견:** 그럴 필요가 없다. 색인·검색·이력·감사 저장은 **전부 `Blocking.call/run`을 지난다.** 거기서 `Mono.deferContextual`로 Context의 추적 ID를 꺼내 작업 스레드의 MDC에 심고 `finally`로 지우면, 하위 계층이 `traceId`를 인자로 받아 끌고 다니지 않아도 로그가 요청과 이어진다. 새 의존성도, 이벤트 루프에 스레드 로컬을 심는 일도 없다. 로그 패턴은 `logging.pattern.correlation: "[%X{traceId:-}] "` 한 줄이면 되고 `logback-spring.xml`이 필요 없다.
- **결정/해결:** `TraceId`(Reactor Context 키) + `TraceIdWebFilter`(체인 맨 앞, `SecurityWebFiltersOrder.FIRST`) + `Blocking`의 MDC 주입. 리액티브 스레드에서 도는 `ChatService`는 MDC가 비므로 `traceId`를 인자로 직접 남긴다.
- **주의 1 — 인바운드 헤더를 신뢰하지 않는다.** 클라이언트가 보낸 `X-Trace-Id`를 그대로 쓰면 감사 기록의 상관관계 키를 요청자가 고를 수 있다. 항상 서버가 발급한다.
- **주의 2 — 스레드풀은 스레드를 재사용한다.** 추적 ID가 없는 작업이 앞선 요청의 ID를 물려받지 않도록, 심지 않을 때는 `MDC.remove`한다.
- **영향:** REL-3, SEC-3, `common/{TraceId,TraceIdWebFilter,Blocking}`, `chat/api/ChatController`
- **다음 주의:** 컨트롤러가 `UUID.randomUUID()`로 추적 ID를 따로 만들면 응답 헤더의 값과 감사 기록의 값이 갈린다. 발급처는 하나여야 한다. 로그에는 질문·답변·조각 **원문을 남기지 않는다**(SEC-3) — 길이·개수·식별자만 남긴다. 전문은 감사 로그가 맡는다(S5, E5).

## [2026-07-10] TEST — Testcontainers ES: "HTTP가 응답한다"와 "elastic 사용자가 준비됐다"는 다르다
- **상황:** `ElasticsearchAuthTest`가 간헐적으로 `unable to authenticate user [elastic]`로 실패했다. 재실행하면 통과.
- **발견:** 대기 전략이 `forStatusCodeMatching(200 or 401)`이었다. 보안이 켜진 ES는 HTTP 계층이 먼저 401을 돌려주기 시작하고, `elastic` 사용자를 담는 **네이티브 렘은 그 뒤에 초기화**된다. 그 틈에 인증하면 실패한다. 401을 "떴다"의 신호로 쓴 것이 원인이다.
- **결정/해결:** `Wait.forHttp(...).withBasicCredentials("elastic", 비밀번호).forStatusCode(200)` — "응답한다"가 아니라 **"자격증명이 실제로 통한다"**를 기다린다.
- **영향:** `ElasticsearchAuthTest`
- **다음 주의:** 컨테이너 대기 조건은 **테스트가 실제로 의존하는 상태**여야 한다. 포트가 열렸다·응답이 왔다는 준비 완료가 아니다. 간헐 실패는 커밋 훅(전체 테스트 실행)을 무작위로 막고, "red를 무시해도 된다"는 습관을 만든다.

## [2026-07-10] OPS — 컨테이너 헬스체크의 `localhost`는 ::1로 풀린다 (서버는 IPv4에만 바인딩)
- **상황:** `frontend` 헬스체크를 `wget -q -O - http://localhost:3000/api/health`로 넣었다. 백엔드도 `curl -sf http://localhost:9090/...`.
- **발견:** 컨테이너가 `unhealthy`로 떨어졌다. 로그는 `wget: can't connect to remote host: Connection refused`. 그런데 라우트는 멀쩡했다 — `127.0.0.1`로는 200, `localhost`로는 거부. Alpine에서 `localhost`가 **`::1`로 먼저 풀리는데 Next.js(standalone)는 `0.0.0.0`, 즉 IPv4에만 바인딩**한다. 애플리케이션은 정상인데 헬스체크만 실패한다.
- **결정/해결:** 헬스체크 URL을 `127.0.0.1`로 고정했다(backend·frontend 둘 다).
- **영향:** REL-5, `docker-compose.yml`
- **다음 주의:** **헬스체크는 반드시 실제로 통과하는 것을 눈으로 본다.** `depends_on: condition: service_healthy`는 헬스체크가 틀리면 조용히 기동을 막거나(타임아웃) 영영 unhealthy로 남는다. 그리고 `docker ps` 상태를 `grep -c healthy`로 검사하면 **`unhealthy`가 매치된다** — 이 오탐 때문에 처음엔 통과한 줄 알았다. 상태 문자열은 정확히 비교한다.

## [2026-07-10] SEC — 경로 정확 매칭 인가는 엔드포인트가 늘어나면 조용히 뚫린다
- **상황:** 재색인 API를 붙이기 직전. 인가 규칙이 `pathMatchers(POST, "/api/index")` 하나였다.
- **발견:** 정확 매칭이라 하위 경로는 아무것도 막지 않는다. `/api/index/{docKey}/reindex`는 `.anyExchange().authenticated()`로 떨어져 **인증만 된 일반 USER에게 열린다.** 실측: USER 토큰으로 호출하니 403이 아니라 **404**가 돌아왔다 — 보안 필터를 통과해 핸들러 매핑까지 갔다는 뜻이다. 핸들러가 없어서 404였을 뿐, 컨트롤러가 있었다면 200이다.
- **결정/해결:** `pathMatchers("/api/index", "/api/index/**").hasRole("ADMIN")` — 메서드와 하위 경로를 가리지 않는다. **엔드포인트를 만들기 전에** 인가 테스트를 먼저 써서 red(404)를 확인하고 고쳤다.
- **영향:** SEC-2("권한 검사 누락 가능 경로가 없어야 함"), `auth/SecurityConfig`
- **다음 주의:** **인가 테스트는 핸들러가 없어도 의미가 있다.** 403을 기대했는데 404가 오면 그것은 "없는 엔드포인트"가 아니라 "인가가 걸리지 않는 경로"다. 두 상태를 구분하지 못하면 다음 컨트롤러가 조용히 문을 연다. `SEC-2`의 "누락 가능 경로가 없어야 함"은 지금 있는 경로가 아니라 **앞으로 생길 경로**에 관한 요구다.

## [2026-07-10] IDX — 재색인은 재업로드가 아니다 (진입점 둘, 파이프라인 하나)
- **상황:** S16이 "재색인은 원본에서 수행"을 결정해 뒀는데 `FileStorage.read()`의 프로덕션 호출자가 0개였다. 결정만 있고 진입점이 없었다.
- **발견:** 재업로드(S17)와 재색인(S16)은 다른 일이다. 전자는 새 파일을 받고, 후자는 보관된 원본을 다시 읽는다. 그러나 그 뒤 파이프라인(추출→청킹→임베딩 전량→bulk 색인→구버전 삭제)은 **완전히 같아야 한다** — 다르면 두 경로 중 하나가 S17의 순서 보장을 잃는다.
- **결정/해결:** 공통 사설 메서드 하나에 `Supplier<String> storageKey`만 다르게 넘긴다. 업로드는 `() -> fileStorage.store(...)`, 재색인은 `document::storageKey`. Supplier라서 **임베딩이 끝난 뒤에 평가**되고, 그 덕에 임베딩 실패 시 고아 원본 파일이 남지 않는다(기존 동작 보존).
- **주의 1:** 재색인은 `UploadValidator`를 부르지 않는다. 그 바이트는 업로드 때 이미 통과했고, **MIME 타입은 어디에도 저장되지 않는다**(document 테이블에 없다). 검증을 부르면 MIME 정확 매칭에서 반드시 실패한다.
- **주의 2:** 접근 태그는 요청이 아니라 **document의 현재 값**을 쓴다(S18). 재색인 요청에 태그 인자를 두지 않는 것이 유일하게 안전한 설계다 — 인자가 있으면 언젠가 그것으로 태그가 바뀐다.
- **주의 3:** `docKey`는 업로드 때 사용자가 정하는 임의 문자열이라 `/`가 들어갈 수 있다. 경로 변수(`/api/index/{docKey}/reindex`)로는 주소를 만들 수 없어 쿼리 파라미터로 받는다.
- **영향:** S16, S17, S18, E1, E9, `idx/service/IndexingService`

## [2026-07-10] ES/SPRING — 실측으로만 확인되는 두 가지 (매핑 차원, @ResponseStatus)
- **상황:** 임베딩 차원이 바뀐 채 재색인하면 ES가 거부한다. 임베딩 비용을 쓰기 **전에** 막고 싶었다.
- **발견 1 — 매핑 차원 읽기:** `GetMappingResponse`에는 `result()`가 **없다.** `client.indices().getMapping(g -> g.index(name)).get(name).mappings().properties().get("embedding").denseVector().dims()` 다(elasticsearch-java 9.4.3). 인덱스가 없으면 `getMapping`이 404를 던지므로 `exists()`를 먼저 본다. `javap`로 실제 시그니처를 확인하고 나서야 컴파일됐다.
- **발견 2 — `@ResponseStatus`:** 이 프로젝트에는 예외→상태 매핑이 하나도 없었다(`UploadRejectedException`도 없다). WebFlux가 `@ResponseStatus`가 붙은 예외를 존중하는지 문서로 믿지 않고 테스트로 확인했다 — **동작한다**(404, 409).
- **결정/해결:** `ChunkRepository.indexedDimensions()`(`OptionalInt`)로 기존 인덱스 차원을 읽고, 임베딩 **전에** 설정 차원과 비교해 다르면 `EmbeddingDimensionMismatchException`(409). 실패가 확정된 작업에 임베딩 비용을 쓰지 않고, 구버전 조각도 건드리지 않는다(S8-3).
- **다음 주의:** "거부했다"와 "돈을 쓰고 거부했다"는 다르다. 테스트는 예외 타입만이 아니라 **`embed()`가 한 번도 불리지 않았음**을 단언한다. 차원은 인덱스 생성 시 고정되므로, 차원을 바꾸려면 새 인덱스가 필요하다(`docs/03`) — 재색인 API가 해결해 주지 않는다.

## [2026-07-10] OBS — 실패한 요청만 추적이 끊긴다 (에러 핸들러는 MDC를 보지 않는다)
- **상황:** REL-3을 "마감"했다고 보고한 뒤, 500 응답의 `X-Trace-Id`를 들고 로그를 뒤졌더니 **없었다.**
- **발견:** 500·404를 찍는 것은 우리 코드가 아니라 스프링의 `AbstractErrorWebExceptionHandler`다. 그 로그 줄은 MDC가 아니라 `exchange.getLogPrefix()`를 쓴다. MDC는 `Blocking.call` 안에서만 심으므로 리액티브 스레드에서 도는 에러 핸들러에는 비어 있다. 정상 요청은 멀쩡하고 **실패한 요청만** 추적이 끊긴다 — 하필 추적이 가장 필요한 쪽이다.
- **결정/해결:** 두 가지. (1) `TraceIdWebFilter`가 `ServerWebExchange.LOG_ID_ATTRIBUTE`를 추적 ID로 덮어쓴다. `getLogPrefix()`는 이 속성을 **매번 다시 읽어** 캐시를 무효화한다(바이트코드로 확인: 필드 `logId`와 비교 후 재계산). 그래서 프레임워크의 모든 로그 줄이 우리 ID를 접두사로 쓴다. (2) `DefaultErrorAttributes`를 확장해 실패 본문에 `traceId`를 싣는다. 기본 본문의 `requestId`는 Netty 식별자라 우리 로그 어디에도 없다.
- **영향:** REL-3, `common/{TraceIdWebFilter,TraceIdErrorAttributes}`
- **다음 주의:** **"관측성을 붙였다"를 정상 경로로만 확인하지 말 것.** 일부러 500을 내고(원본 파일을 지운 문서를 재색인) 응답 헤더의 ID로 로그를 grep해야 진짜 확인이다. 그리고 `server.error.include-message`는 켜지 않는다 — 켜면 모든 예외의 내부 문구가 밖으로 나간다(SEC-3). 나가는 것은 추적 ID뿐이고 원인은 그 ID로 로그에서 찾는다.

## [2026-07-10] API — 도메인 예외에 상태가 없으면 정상 거부가 500으로 나간다
- **상황:** `UploadRejectedException`(SEC-4 허용목록 위반)에 `@ResponseStatus`가 없었다. 이 프로젝트에 예외→상태 매핑이 **하나도** 없었다.
- **발견:** 거부 자체는 정확히 동작한다(document 0행, ES 조각 0개). 그러나 응답이 **500 Internal Server Error**다. 클라이언트 잘못을 서버 장애로 보고하는 셈이고, 운영에서 정상 거부와 진짜 장애를 구분할 수 없다. 본문에 예외 메시지는 실리지 않으므로 유출은 없다.
- **결정/해결:** `@ResponseStatus(BAD_REQUEST)`. WebFlux가 예외의 `@ResponseStatus`를 존중한다는 것은 문서가 아니라 테스트로 확인했다(404·409·400).
- **함께 발견(정정됨 — 아래 항목 참조):** 0바이트 파일도 400이지만, 어느 계층이 막는지는 클라이언트마다 다르다. 처음에 "검증기에 도달하지 않는 죽은 코드"라고 적었는데 **틀렸다.**
- **다음 주의:** 컨트롤러 테스트로 MIME 불일치를 재현하려 했더니 **200이 나왔다.** `ResourceHttpMessageWriter`가 파일명에서 `Content-Type`을 다시 유도해 버려 `MultipartBodyBuilder`로는 MIME을 속일 수 없다. 실제 클라이언트(curl `;type=`)는 속일 수 있다. **대역이 재현하지 못하는 것을 "테스트했다"고 적지 말 것** — 그 경로는 단위 테스트가 막는다.

## [2026-07-10] DATA — 스키마에 있는 컬럼이 코드에 없으면 조용히 NULL로 남는다
- **상황:** `docs/03`과 `V1` 마이그레이션에 `document.uploaded_by FK→app_user`가 있는데 `DocumentEntity`에 매핑이 없었다. 실측: 문서 4행, 채워진 행 **0**.
- **발견:** 파보니 더 컸다. `AuditLogRepository`를 부르는 프로덕션 코드는 `ChatService` 하나뿐이고 `REQ-AUDIT`에는 "색인"이라는 단어가 없다. **관리자가 restricted 문서를 색인해도 어디에도 기록이 남지 않았다.** `uploaded_by`가 그것이 남을 유일한 자리였다.
- **결정/해결:** `uploaded_by`를 채운다. 계약은 `upsert(..., uploadedBy)`에서 **`null`은 "기존 값 유지"** — 재업로드는 갱신하고(새로 올린 사람이 현재 책임자다), 재색인은 유지한다(다시 올린 게 아니다). 색인을 `audit_log`에도 남길지는 `AUDIT` 태그라 보류할 수 없어 사람에게 물었고, ANSWERED로 기록했다(OQ-006).
- **다음 주의:** 마이그레이션에만 있고 엔티티에 없는 컬럼은 **테스트가 절대 잡지 못한다.** 삽입도 조회도 성공하고 값만 NULL이다. 스키마와 엔티티의 컬럼 집합을 대조하는 것이 유일한 방법이다. 그리고 `docs/08` §D.2의 자동 차단 태그(`AUDIT`·`DATA` 등)는 **OPEN OQ로 존재할 수 없다** — 보류가 아니라 물어야 한다.

## [2026-07-10] BUG — 빈 Mono는 200 OK가 된다 (`DataBufferUtils.join`의 조용한 성공)
- **상황:** 앞선 항목에서 "0바이트 파일은 IndexingService에 도달조차 하지 않는다. 프레임워크가 400으로 끊는다"고 적었다. **틀렸다.** 근거가 "색인 시작 로그가 없다"였는데, `validator.validate()`가 그 `log.info()` **바로 앞줄**에서 실행된다. 로그가 없다는 것은 도달하지 않았다는 뜻이 아니라 로그 줄에 닿기 전에 거부됐다는 뜻이다.
- **발견 1 — 클라이언트마다 다르다.** 0바이트 업로드에서 **curl**은 멀티파트 파트를 아예 빼버린다 → `Required request part 'file' is not present.` (프레임워크 400, 검증기 미실행). **WebTestClient**는 길이 0 버퍼를 보낸다 → `빈 파일은 색인할 수 없다` (우리 검증기 400). `server.error.include-message=always`로 띄워 메시지를 보고서야 구분됐다.
- **발견 2 — 진짜 구멍.** 파트는 있는데 내용 Flux가 **비어 있으면** `DataBufferUtils.join()`이 **빈 Mono**를 낸다. 그러면 `map`·`flatMap`이 통째로 건너뛰어지고 핸들러가 아무 값도 내지 않는다. WebFlux는 빈 `Mono<T>`를 **200 OK + 빈 본문**으로 내보낸다. 색인은 없었는데 관리자는 성공으로 읽는다. 단위 테스트로 재현: `expected: onError(); actual: onComplete()`.
- **결정/해결:** `.defaultIfEmpty(new byte[0])`. 빈 바이트를 내려보내 `UploadValidator`가 거부하게 한다(400). 인코더 동작에 기대지 않고 구조로 막는다 (SEC-4).
- **다음 주의:** **리액티브에서 "아무 일도 없었다"는 성공으로 보인다.** `Mono.empty()`가 흐르는 모든 `flatMap` 체인은 조용한 200의 후보다. `join`·`next`·`filter` 뒤에는 빈 흐름이 무엇을 의미하는지 정해야 한다. 그리고 **로그의 부재로 코드 경로를 추론하지 말 것** — 그 로그가 검사 뒤에 있는지 앞에 있는지 먼저 본다.

## [2026-07-10] SEC — 소유권 검사는 "인증됨"과 다르다 (모든 테스트가 자기 세션만 썼다)
- **상황:** 기획 문서 대비 전수 감사 중, `/api/chat/stream`이 요청자가 준 `sessionId`를 **검사 없이** 썼다. `isOwnedBy(sessionId, userId)`는 **이미 있었고** `SessionController`의 조회·삭제는 쓰고 있었다. 채팅 경로만 건너뛰었고, `userId`를 구해놓고 사용하지 않았다.
- **발견:** 인증된 USER가 남의 `sessionId`를 넘기면 (1) 그 사람의 대화 이력이 LLM 프롬프트에 실려 답변으로 새어 나오고 (2) 그 세션에 메시지가 덧붙는다. **접근 태그 필터는 아무 소용이 없다** — 유출되는 것은 문서 조각이 아니라 대화 이력이다. 실측: 대역 `ChatModel`이 받은 프롬프트에 피해자의 문장이 그대로 있었고, 피해자 세션 메시지가 1→3이 됐다.
- **왜 테스트가 다 통과했나:** **기존 테스트가 전부 자기 세션만 썼다.** 남의 세션을 건드려 본 테스트가 하나도 없었다. 인가 테스트는 "권한이 있는 주체가 성공한다"가 아니라 **"권한이 없는 주체가 실패한다"**를 봐야 한다. 후자를 쓰지 않으면 통과는 아무것도 증명하지 않는다.
- **결정/해결:** `isOwnedBy` 확인 후 실패 시 **404**(403 아님 — 403은 "그 ID의 세션이 존재한다"를 알려준다). `SessionController`와 같은 방식.
- **다음 주의:** 세션 ID가 UUIDv4라 추측이 어렵다는 것은 통제가 아니다(SEC-2: "권한 검사 누락 가능 경로가 없어야 함"). 그리고 이번 세션에서 **인가 구멍이 두 번 나왔다** — `/api/index/**`(경로 정확 매칭)와 여기(소유권 미검사). 둘 다 "엔드포인트가 생기고 나서" 드러났다. 새 엔드포인트를 만들 때 **인가 테스트를 먼저** 쓴다.

## [2026-07-10] SEC — 에러 표면은 한 곳만 막으면 소용없다
- **상황:** HTTP 에러 본문에서 예외 메시지를 막았다(`server.error.include-message`를 켜지 않고 `traceId`만 내보낸다). 그런데 SSE `error` 이벤트는 `error.getMessage()`를 그대로 브라우저로 보냈다.
- **발견:** 같은 정보(ES 인덱스명, 게이트웨이 주소, 예외 문구)가 다른 통로로 나갔다. 실측: LiteLLM을 멈추고 질문하니 `event:error` 뒤에 예외 문구가 그대로 실렸다.
- **결정/해결:** 고정 문구 + 추적 ID. 원인은 로그에 남고 사용자는 그 ID로 신고한다. `S8-3`의 "부분 응답 후 끊김을 사용자가 구분 가능"은 `sources → text… → error`(done 없음) **시퀀스**가 만족하는 것이지 문구가 아니다.
- **다음 주의:** 에러가 나가는 통로를 세어 본다 — HTTP 본문, SSE 이벤트, 로그, 헤더. 한 곳을 막고 "막았다"고 적지 않는다.

## [2026-07-10] OPS — 문서가 이름까지 댄 설정이 죽어 있었다
- **상황:** `REL-4`가 *"임베딩 모델, N턴, top-k, **분석기**, RateLimit, 저장 경로 등은 설정값. 코드 하드코딩 금지"* 라고 이름을 댔다.
- **발견:** `ElasticsearchChunkRepository`가 `.analyzer("nori")`로 박고 있었고, `.env.example`의 `SEARCH_ANALYZER=nori`는 **읽는 코드가 없는 죽은 설정**이었다. `IdxProperties`의 클래스 주석은 "전부 설정값이며 코드에 하드코딩하지 않는다 (REL-4)"라고 적혀 있었다. **주석이 코드보다 앞서 있었다.**
- **결정/해결:** 생성자 주입. 새 인터페이스는 만들지 않았다 — `E16`이 요구하는 것은 "언어별 교체 가능"이고 설정값이면 충족된다.
- **검증:** "설정을 넣었다"가 아니라 **실제 ES 매핑을 읽어** 확인했다(`standard`로 만든 인덱스의 `chunk_text.analyzer`가 `standard`인지). 설정이 매핑에 닿지 않으면 외부화한 것이 아니다.
- **다음 주의:** `.env.example`의 변수와 `application.yml`·코드가 읽는 키를 주기적으로 대조한다. **선언만 있고 읽지 않는 설정은 문서가 거짓말을 하게 만든다**(MAINT-3). 분석기를 바꾸면 새 인덱스가 필요하다 — 차원 변경과 같은 성질이다.

## [2026-07-10] PERF — "N턴 제한"은 길이 상한이 아니다
- **상황:** `PERF-5`는 두 줄이다. "컨텍스트 윈도우는 최근 N턴으로 제한"과 "**LLM 컨텍스트 길이 초과를 방지하는 상한(설정)**". 앞줄만 구현되어 있었고, `RecentTurnsContextAssembler`의 주석은 "무한 누적을 막아 LLM 컨텍스트 길이 초과를 방지한다 (PERF-5)"라고 **주장**했다.
- **발견:** 턴 수는 길이를 잡지 못한다. 긴 메시지 하나면 컨텍스트가 넘친다. 코드에 토큰·문자 상한이 하나도 없었다(grep 0건).
- **결정/해결:** ① 조립기에 토큰 예산(`max-context-tokens`) — N턴으로 자른 뒤 **오래된 쪽부터** 버린다. 예산은 턴이 아니라 **메시지 단위**로 잘리므로 반쪽 턴이 남을 수 있다. 상한의 목적은 대화 모양이 아니라 컨텍스트 초과 방지다. 메시지 하나가 예산보다 크면 컨텍스트는 비어 있다 — "최근 것은 무조건 담는다"면 그것은 상한이 아니다. ② 질문 상한(`max-question-chars`) — 지금 질문은 자를 수 없다(잘라내면 사용자가 묻지 않은 것을 묻게 된다). 400으로 거부한다.
- **다음 주의:** 단언이 실패했을 때 **기댓값을 관측값에 맞추지 않았다.** 예산 35에 10토큰 메시지 4개를 기대했다가 3개가 나왔을 때, 산수를 다시 해 예산을 40으로 고쳤고(4개가 맞다), 메시지 단위로 잘린다는 사실은 별도 테스트로 드러냈다. 그리고 **주석은 검증이 아니다** — "PERF-5를 방지한다"고 적힌 클래스가 PERF-5를 절반만 지키고 있었다.

## [2026-07-10] BFF — 상태 코드를 뭉개면 백엔드의 정확한 인가 응답이 무의미해진다
- **상황:** 코어에 세션 소유권 검사(404)와 질문 길이 상한(400)을 넣은 직후, 프론트를 감사했다.
- **발견:** `app/api/chat/stream/route.ts`가 **모든 non-2xx를 502로 뭉개고 있었다**(`if (!upstream.ok) return new Response(..., { status: 502 })`). 방금 만든 404·400도, 만료 토큰의 401도 전부 502가 됐다. JSON 프록시(`lib/core.ts`)는 상태를 그대로 넘기는데 스트림 라우트만 달랐다.
- **왜 치명적인가:** 401을 볼 수 없으면 **토큰 갱신을 붙여도 재로그인을 유도할 수 없다.** 백엔드에서 인가를 정확하게 만들어놓고 BFF가 그것을 지워버린 셈이다.
- **결정/해결:** `relayFailure`로 상태·본문을 보존한다. 502는 **"상태는 성공인데 본문이 없을 때"** 만이다 — 그것이 진짜 게이트웨이 오류다. `core.ts`가 `@/auth`를 import해 `node --test`로 못 켜므로 순수 모듈 `lib/upstream.ts`로 뺐다.
- **다음 주의:** 백엔드에 새 상태 코드를 추가하면 **그것이 사용자에게 도달하는 경로를 끝까지 따라간다.** 프록시·게이트웨이·번역 계층이 중간에서 지운다. 그리고 **테스트할 수 있게 하려면 순수 부분을 분리해야 한다** — 프레임워크를 import하는 모듈은 테스트 러너가 켜지 못한다.

## [2026-07-10] AUTH — Keycloak refresh token은 회전한다
- **상황:** 프론트에 토큰 갱신이 없어 사용자가 5분마다 재로그인해야 했다. `auth.ts`가 `account.access_token`만 담고 `refresh_token`·만료시각을 버렸다.
- **실측:** access token **300초**, refresh token **1800초**. 그리고 Keycloak은 **처음부터 `refresh_token`을 발급하고 있었다.** 프론트가 버리고 있었을 뿐이다.
- **발견:** `grant_type=refresh_token`으로 갱신하면 Keycloak이 **새 refresh_token을 함께 준다(회전).** curl로 확인했다 — 옛 값과 다르다. 회전된 값을 저장하지 않으면 30분 뒤가 아니라 **다음 갱신에서 바로** 끊긴다.
- **결정/해결:** `lib/token.ts`(순수)에 `isExpired`(30초 여유) + `refreshAccessToken`. 갱신은 **던지지 않고** `error` 필드로 실패를 표시한다 — `jwt` 콜백에서 예외가 나면 Auth.js의 동작이 예측하기 어렵다. 실패하면 `session.error`가 서고, `proxyToCore`와 `page.tsx`가 그것을 보고 만료된 베어러를 상류로 보내지 않는다.
- **다음 주의:** Auth.js의 세션 쿠키(기본 30일)와 그 안에 든 access token(5분)의 수명은 **완전히 다르다.** `auth()`가 세션을 돌려준다고 그 안의 토큰이 살아 있는 것이 아니다. `if (!session)`만 보는 게이트는 갱신 실패를 잡지 못한다 — `session.error`도 봐야 한다.

## [2026-07-10] IDX — 상수로 박힌 "버전"은 버전이 아니다
- **상황:** `document.chunking_version`이 `PostgresDocumentRepository`의 상수 `"token-v0"`에서 왔고, `replaceWith`는 그것을 갱신하지 않았다.
- **발견:** 두 겹의 문제였다. (1) 재색인해도 값이 갱신되지 않아 대상 목록에서 사라지지 않는다. (2) 그 상수는 **실제 `ChunkingStrategy`와 아무 연결이 없다** — 전략을 교체해도 상수는 그대로다. 그런데 상수 옆 주석은 *"청킹 전략이 바뀌면 재색인 대상을 이 값으로 식별한다 (E11)"* 라고 적혀 있었다. **또 주석이 코드보다 앞서 있었다.**
- **결정/해결:** 임베딩과 대칭으로 만들었다 — `EmbeddingClient.spec().model()`이 있듯 `ChunkingStrategy.version()`을 두고, `IndexingService`가 매 색인마다 넘긴다. persistence의 상수는 지웠다.
- **주의:** `version()`을 `"token-v0"`으로 **유지**했다. 값을 바꾸면 이미 색인된 모든 문서가 갑자기 재색인 대상이 된다. 같은 이유로 `chunkSizeTokens`를 버전에 넣지 않았다.
- **다음 주의:** "무엇으로 색인했는가"를 기록하는 필드는 **그것을 실제로 수행한 컴포넌트가 알려줘야 한다.** 다른 계층의 상수로 두면 둘이 어긋나는 순간을 아무도 모른다. 그리고 기록만 고쳤다고 조회까지 된 것은 아니다 — `staleDocuments()`는 여전히 임베딩 모델만 본다(OQ-010).

## [2026-07-11] REL — 무한 대기는 세 곳이었고, 두 곳만 닫혔다 (R-2)
- **상황:** 외부 호출에 타임아웃이 없어(REL-1 위반) 게이트웨이가 조용히 멈추면 boundedElastic 스레드가 영구히 묶인다. 실측으로 확인했었다(응답 없는 소켓 → 120초 뒤 소켓이 닫힐 때까지 반환 없음).
- **JDK HttpClient에는 read 타임아웃 기본값이 없다.** `HttpClient.newBuilder().connectTimeout(...)`은 **연결** 상한일 뿐, 연결 후 무응답은 못 막는다. read는 `JdkClientHttpRequestFactory.setReadTimeout(...)`으로 따로 줘야 한다. 둘 다 줘야 무한 대기가 닫힌다.
- **SSE 유휴 타임아웃은 `Flux.timeout(Duration)`이다 — 전체 응답 상한이 아니라 토큰 '사이' 상한.** LLM이 조용히 멈추면 에러가 emit되지 않아 `onErrorResume`이 돌지 않는다(done도 error도 안 나감 → 브라우저 무한 로딩). `timeout`이 `TimeoutException`을 만들어 그때서야 `onErrorResume`이 `ChatEvent.Error`로 닫는다. 토큰이 계속 오는 긴 답변은 잘리지 않는다.
- **뮤테이션으로 두 방어를 각각 검증했다.** 프로덕션에서 타임아웃을 빼면 테스트가 **실패가 아니라 매달린다**(Gradle 데몬이 죽었다) — `block()`/`assertTimeout`에 상한을 주지 않으면 뮤테이션 테스트가 무의미하다. `block(Duration.ofSeconds(5))`로 고친 뒤에야 red가 드러났다.
- **남은 것:** **ES transport 타임아웃은 아직 없다.** `ElasticsearchTransportConfig.Builder`에 타임아웃 메서드가 없어(이전 세션에서 확인) RestClient 계층에서 소켓 타임아웃을 줘야 한다. R-3/R-11(유령 문서)과 같은 색인 경로라 함께 다룬다. **"타임아웃을 넣었다"가 "모든 외부 호출에 넣었다"는 아니다** — 세 곳(임베딩·SSE·ES) 중 둘만 닫혔다.

## [2026-07-11] IDX — 두 저장소 쓰기는 원자적일 수 없다, 그래서 순서가 방어다 (R-3, R-11)
- **상황:** 색인이 PG upsert(즉시 커밋) → ES indexAll → deleteStale 순이었다. ES가 부분 장애(쓰기 차단·매핑 오류·bulk 거부)면 PG 행은 이미 *현재 모델*로 커밋돼 유령 문서가 된다 — 검색 불가인데 `staleDocuments()`(모델 불일치로 찾음)에도 안 잡힌다. 실측으로 확인했었다(`index.blocks.write=true`).
- **결정(사람):** ES 먼저, 성공 후 PG. status 컬럼 2단계(더 견고하지만 마이그레이션+검색 경로 변경)는 v0 뼈대 범위 밖이라 하지 않는다.
- **핵심 난점:** ES 조각은 `document_id`가 필요한데 그 id는 지금까지 upsert가 커밋하며 발급했다. ES를 먼저 하려면 id가 커밋보다 **먼저** 필요하다. **해결: document id를 doc_key에서 결정적으로 유도**(`DocumentId.of(docKey)` = `UUID.nameUUIDFromBytes`). doc_key가 document의 정체성이므로(S17) id도 그로부터 나오는 게 자연스럽다. 덕분에 `upsert` 시그니처를 안 바꾸고(=PG 테스트 16곳 안 건드리고) 커밋 전에 조각을 조립한다. 신규는 서비스·upsert가 같은 함수로 같은 id를 얻고, 재색인은 그 값이 저장된 id와 같다.
- **대역 주의:** 서비스가 커밋 전에 `DocumentId.of(docKey)`로 조각을 만드므로, **모든 DocumentRepository 대역**(`FakeDocumentRepository`, `InMemoryDocumentRepository`)도 id를 같은 함수로 발급해야 조각의 `document_id`와 저장된 `document.id`가 맞는다. "doc-N" 임의 id를 쓰면 통합 테스트에서 `findByDocumentId`가 빈 결과를 준다. 이걸로 3건이 깨졌다 잡았다.
- **ES bulk는 원자적이지 않다(R-11).** `response.errors()`가 참이어도 성공한 op는 이미 반영됐다. 예외만 던지면 반쪽 색인이 구 run과 공존한다. **한 번의 `indexAll`은 한 문서·한 실행의 조각만 담으므로**, `chunks.get(0)`의 `document_id`+`indexing_run_id`로 이번 run 조각을 정확히 겨냥해 롤백 삭제한 뒤 던진다. 통합 테스트는 한 조각의 `embeddingDim`을 3으로 바꿔(`EmbeddedChunk`가 벡터 길이==embeddingDim만 검사하므로 통과) ES 매핑(dims=4)을 위반시켜 부분 실패를 만들었다. 뮤테이션(롤백 제거)으로 red 확인.
- **남은 것:** ES **완전** 장애는 안전하다(차원 검사가 upsert 전에 ES를 쳐서 먼저 실패). PG-실패-after-ES-성공(드묾, 로컬 PG)은 ES 조각만 남지만 태그로 필터돼 안전하고 다음 색인에서 정리된다. ES transport 타임아웃(R-2 잔여)은 아직 없다 — 다음.

## [2026-07-11] ES 클라이언트는 기본이 무한 대기다 — 저수준으로 내려가야 타임아웃이 걸린다 (R-2)
- **실측:** 먹통 소켓(연결만 받고 무응답)에 대해 현재 ES 클라이언트가 **200초를 넘겨도** 반환하지 않았다. Rest5의 기본 소켓 타임아웃 상수는 30000ms이지만 fluent `ElasticsearchTransportConfig` 경로에는 **적용되지 않았고**, 기본 응답 타임아웃은 `DEFAULT_RESPONSE_TIMEOUT_MILLIS = 0`(무한)이다.
- **막다른 길:** `ElasticsearchTransportConfig.Builder`에도 그 부모 `AbstractBuilder`에도 타임아웃 훅이 없다(host/auth/ssl/compression/transportOptions뿐). `transportOptions`는 요청별 헤더·쿼리파라미터지 소켓 타임아웃이 아니다.
- **유일한 길:** 저수준 `Rest5Client.builder(uri).setHttpClient(customAsyncClient)`로 Apache HttpClient5 비동기 클라이언트를 직접 물린다. `Rest5ClientBuilder`엔 타임아웃 setter가 없고 `setHttpClient(CloseableHttpAsyncClient)`뿐이다. 커스텀 클라이언트에 `RequestConfig.custom().setConnectTimeout(Timeout).setResponseTimeout(Timeout)`를 건다. **응답 타임아웃(responseTimeout)이 "연결은 됐는데 응답이 없다"를 잡는 핵심**이다.
- **auth를 직접 보존해야 한다.** fluent의 `usernameAndPassword`를 버렸으므로, 커스텀 클라이언트에 `BasicCredentialsProvider`(와일드카드 `AuthScope(null, -1)` — 단일 호스트라 스코프 좁힐 필요 없음)를 세운다. ES는 security 켜지면 401 챌린지를 주므로 non-preemptive basic auth로 충분하다. 실 보안 컨테이너 테스트(`ElasticsearchAuthTest`, 401 케이스 포함)가 통과해 배선을 확인했다.
- **검증:** 먹통 소켓 테스트를 `assertTimeoutPreemptively(8s, ...)`로 감싸(행 방지) 응답 타임아웃 500ms 안에 예외로 끝남을 확인. 뮤테이션(responseTimeout=0)으로 red 확인. 이로써 R-2의 세 외부 호출(임베딩·SSE·ES)이 모두 상한을 갖는다.

## [2026-07-11] 배포 설정 위생 — 설정이 코드에 닿지 않으면 없는 것이다 (R-6, R-7, R-15)
- **R-6 (설정 전달 누락):** `application.yml`이 읽는 env var와 `docker-compose.yml`의 backend `environment:`를 대조하니 8개+가 전달되지 않았다(SEARCH_ANALYZER, ES_INDEX_NAME, CHUNK_SIZE_TOKENS, MAX_UPLOAD_BYTES, MAX_CONTEXT_TOKENS, MAX_QUESTION_CHARS, SEARCH_BM25_BOOST, SEARCH_VECTOR_BOOST) + 이번에 추가한 타임아웃 5개. **왜 안 드러났나:** 개발은 백엔드를 `bootRun`으로 돌려 `.env`를 직접 읽지만, 컨테이너 백엔드는 compose `environment`만 본다(override에서 `app` 프로파일로 dev 제외). 그래서 REL-4("설정값, 하드코딩 금지")가 **개발에서만 참**이었다. 운영자가 nori→english로 바꿔도 아무 일도 안 일어난다. → backend `environment`에 `KEY: ${KEY:-기본값}`(기본값은 yml과 동일)로 전부 추가.
- **검증법:** `docker compose --env-file .env.example -f docker-compose.yml config`가 앵커·env를 해석해 최종 서비스 정의를 뱉는다. 여기서 backend env에 키가 있는지 grep으로 확인했다. compose는 유닛 테스트가 없으니 이 resolve가 검증이다.
- **R-7 (로그 회전):** 어느 서비스에도 `logging:` 설정이 없었다. Docker 기본 json-file은 크기 제한이 없어 디스크를 채운다 → PG WAL 실패·ES read-only. YAML 앵커(`x-logging: &default-logging`, max-size 10m/max-file 5)를 만들어 6개 서비스 전부에 `logging: *default-logging`으로 걸었다. config resolve에서 max-size 블록 수로 확인.
- **R-15 (죽은 설정):** `application.yml`이 `rate-limit-enabled: false`를 **리터럴로** 박아, `.env`의 `RATE_LIMIT_ENABLED`가 무시됐다. `${RATE_LIMIT_ENABLED:false}`로 바꿔 다른 튜너블과 같게 만들었다. v0 필터는 어차피 통과시키므로 기능 변화는 없지만, **설정이 있는데 무시되는 것 자체가 운영자를 오도한다.** "설정이 존재한다"와 "그 설정이 코드 경로에 닿는다"는 다르다 — 이 셋의 공통 교훈이다.

## [2026-07-11] JWT는 서명·만료만으로 부족하다 — issuer·audience로 대상을 좁힌다 (R-16)
- **상황:** 백엔드가 `jwk-set-uri`만 써서 서명·만료만 검증했다. 같은 realm 키로 서명된 토큰이면 다른 클라이언트용이라도 통과했다.
- **왜 실재하나:** 명시 클라이언트는 `llmhub-backend`(bearerOnly) 하나지만, **모든 Keycloak realm에는 기본 클라이언트(account·admin-cli·security-admin-console 등)가 암묵적으로 존재**해 같은 키로 서명된 토큰을 발급할 수 있다. 역할만 맞으면 통과했다.
- **issuer는 정적 문자열 검증이다 — 네트워크 호출이 없다.** `issuer-uri`(OIDC 설정을 빈 생성 때 가져옴 → Keycloak 없이 기동 불가)와 다르다. `NimbusReactiveJwtDecoder.withJwkSetUri(uri).build()` 후 `setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(issuer), audienceValidator))`. jwk-set-uri(내부 호스트)와 issuer(토큰 iss = Keycloak **외부** 호스트명)는 **다른 값**이라 별도 설정이다.
- **audience는 realm이 내보내 줘야 검증할 수 있다.** 기본 Keycloak access token은 다른 클라이언트를 `aud`에 안 넣는다(realm 역할이라 Audience Resolve 매퍼도 안 걸림). `oidc-audience-mapper`(included.client.audience=llmhub-backend)를 프론트 클라이언트/스코프에 걸어야 토큰이 `aud=llmhub-backend`를 담는다. `bootstrap-dev.sh`에 그 매퍼 추가를 넣었다.
- **테스트 공존:** 컨트롤러 테스트들은 `@Primary ReactiveJwtDecoder` 대역을 정의해 crafted Jwt를 바로 돌려준다. 프로덕션 `jwtDecoder` 빈(non-primary)을 추가해도 @Primary가 이겨 충돌하지 않는다. 빈은 인스턴스화되지만 `build()`가 지연 조회라 컨텍스트 로드에 네트워크가 없다. `AudienceValidator`는 순수라 crafted Jwt로 단위 테스트(뮤테이션 확인).
- **검증 경계(정직):** 백엔드 검증기는 단위 테스트로 확정. 실토큰이 `aud`를 담고 issuer가 일치하는지는 **Keycloak을 못 띄워 확인 못 함**(OQ-012). 두 검증 모두 **fail-closed** — 어긋나면 로그인만 막히고(자기 DoS) 인가 우회는 없다. 운영 프론트 클라이언트 프로비저닝은 저장소 밖(dev는 bootstrap-dev.sh)이라 운영도 매퍼가 필요하다.

## [2026-07-11] 세션에 실은 것은 브라우저가 읽는다 — 토큰은 JWT 쿠키에만 둔다 (R-8)
- **상황:** `auth.ts` session 콜백이 `session.accessToken`을 세웠다. Auth.js는 session 콜백의 반환을 그대로 `/api/auth/session` 응답으로 내보내므로, 브라우저 JS가 `fetch('/api/auth/session')`로 베어러를 읽을 수 있었다. "BFF만 토큰을 쥔다"(docs/01)가 깨진다.
- **해결:** session에서 accessToken을 뺀다(error만 남긴다 — 재로그인 판단용, 민감정보 아님). 토큰은 **암호화된 JWT 쿠키**에만 있고, BFF가 `getToken`(next-auth/jwt)으로 **서버측**에서만 꺼낸다(`lib/core.ts` bearerToken). proxyToCore·chat stream 라우트 둘 다 이 헬퍼를 쓴다. `types/next-auth.d.ts`의 Session에서 accessToken 제거(JWT에는 유지).
- **getToken 함정 — salt는 쿠키명과 같아야 복호화된다.** Auth.js는 세션 JWT를 `salt = cookieName`으로 암호화한다. 쿠키명은 https에서 `__Secure-authjs.session-token`, http에서 `authjs.session-token`이다. `secureCookie`를 틀리면 쿠키를 못 찾거나 salt가 어긋나 복호화 실패 → **모든 BFF 호출 401**. 실제 요청 쿠키에 `__Secure-` 접두사가 있는지로 감지해 dev·운영 모두 맞춘다. 큰 토큰이 청크된 쿠키(`.0`,`.1`)는 getToken 내부 SessionStore가 합친다.
- **plumbing:** `next/headers`의 `headers()`(Next 16은 async)로 요청 헤더를 얻어 `getToken({req:{headers}})`에 넘긴다. proxyToCore 3개 호출부에 request를 스레딩하지 않아도 된다(요청 스코프에서 headers()가 동작).
- **검증 경계:** `bearerToken`은 `next/headers`·next-auth를 끌어 `node --test`로 못 켠다. 타입체크·`next build`로 컴파일·타입은 확정했으나, **실토큰 쿠키를 실제로 복호화해 BFF가 여전히 인가하는지**는 실스택(Next+Keycloak 로그인)이 있어야 확인된다 — 프론트 마감 때 브라우저 검증과 함께 본다. fail-closed(틀리면 401)라 보안 우회는 없다.

## [2026-07-11] 감사 공백 — 취소는 doOnComplete를 실행하지 않는다 (R-5)
- **상황:** `ChatService`의 감사 기록이 `doOnComplete`(persist)에서만 일어났다. **Reactor 취소는 doOnComplete를 실행하지 않는다.** 근거(sources)는 첫 토큰보다 먼저 전달되므로(S6), 사용자가 근거를 받고 탭을 닫으면(취소) 근거는 이미 브라우저에 갔는데 감사는 0건이었다 — REQ-AUDIT "채팅 1회 → 감사 1건" 위반. 실측(리뷰): 중도 종료 시 감사 카운트가 안 늘었다.
- **결정(사람):** doFinally로 감사를 옮겨 취소·오류에도 남긴다. 완료/취소/오류를 outcome으로 표시(스키마 변경 승인 → V3 마이그레이션, 기본값 COMPLETE).
- **doFinally는 complete·cancel·error 모든 종료에서 정확히 한 번 돈다.** 이력은 doOnComplete(성공만) 그대로 두고, 감사만 doFinally로 분리했다. 그래서 성공 시 감사는 doFinally에서 한 번(중복 없음), 취소·오류 시에도 한 번. `SignalType`으로 outcome을 정한다(ON_COMPLETE→COMPLETE, CANCEL→CANCELLED, 그 외→ERROR).
- **취소는 in-flight onNext와 겹칠 수 있다.** doFinally가 취소 시 `answer`(StringBuilder)를 읽는데, 그 순간 다른 스레드가 append 중일 수 있다(찢긴 읽기 → 드물게 예외). append와 snapshot 읽기를 `synchronized(answer)`로 묶어 막았다. onComplete·onError는 신호가 직렬화돼 경쟁이 없지만 CANCEL만 예외라 필요하다.
- **테스트로 취소를 만드는 법:** `service.stream(...).take(2).blockLast()` — Sources + 첫 토큰까지 받고 take가 upstream을 취소한다. 비동기 감사 기록은 latch로 기다린다. 뮤테이션(doFinally→doOnComplete)으로 취소·오류 감사 테스트가 red 됨을 확인.
- **동작 변경:** 기존 "오류 시 아무것도 저장 안 함" 테스트를 "이력은 없고 감사는 ERROR"로 바꿨다. 약화가 아니라 R-5 결정(취소·오류도 감사)의 구현이다 — 근거가 전달된 실패도 감사 대상이다.

## [2026-07-11] 무검증 방어를 테스트가 강제하게 만들기 (리뷰 "테스트가 강제 못 하는 것")
- **ManagementPortMatcher(SEC-1):** 관리 포트를 앱 포트와 같게 오설정하면 헬스가 인증 없이 열린다. 그 방어 분기(`management.equals(application)`)가 무검증이라 지워도 통과했다. 테스트로 강제(뮤테이션 확인). **함정: `MockServerHttpRequest` 빌더에 localAddress 설정이 없다** — `ServerHttpRequestDecorator`로 `getLocalAddress()`를 덮어야 한다.
- **TraceIdErrorAttributes(SEC-3):** 에러 본문이 예외 문구를 노출하도록 바꿔도 무검출이었다. traceId는 싣고 내부 문구(ES 인덱스명·주소)는 안 싣는지 검증. **함정: `MockServerWebExchange`는 `LOG_ID_ATTRIBUTE`를 기본값으로 채운다** — "traceId 없음" 케이스는 mock으로 못 만든다(운영에선 TraceIdWebFilter가 항상 심으니 무의미).
- **V0EndToEnd 근거→LLM:** 대역 모델이 **프롬프트를 무시하고 고정 문자열만 흘려**, 근거 주입을 제거해도 통과했다(`contains(근거)`가 매칭되는 곳은 LLM 출력이 아니라 `event:sources` 프레임). 또 주석은 "근거를 되뇐다"고 앞서 있었다. **결정적 단언:** 대역이 시스템 프롬프트를 되뇌게 하고, `"[근거]"`(시스템 프롬프트에만 있고 sources JSON엔 없음) 포함 + 빈 대체 문구 부재를 확인. 근거 주입 제거 시 red 됨을 뮤테이션으로 확인.
- **교훈:** "테스트가 무엇을 강제하지 않는지"도 발견이다. 통과하는 스위트가 방어의 부재를 숨길 수 있다 — 뮤테이션(프로덕션 코드를 일부러 뒤집기)만이 그것을 드러낸다.
