# 코드리뷰 · 2026-07-11 · 전체 코드베이스

- **범위:** `backend/src/{main,test}`, `frontend/`, `docker-compose*.yml`, `docker/keycloak/**`, `backend/Dockerfile`, Flyway V1~V4, `application.yml`
- **기준 커밋:** `dee38b6` (워킹트리 clean)
- **방법:** 8개 관점 finder를 **병렬**(Workflow)로 돌려 각자 코드를 직접 읽어 후보를 냈고(후보 20건), 각 후보를 **적대적 검증자 2인**이 반증 시도(불확실하면 기각)해 확정 9·경계 4·기각 7로 분류했다. 이후 리뷰어가 **상위 발견의 코드를 직접 재확인**했고 둘은 **살아 있는 스택으로 재현**했다.
- **직전 리뷰:** `2026-07-10`(커밋 `1bd59e1`). 그 후 R-1~R-17·L-1~L-12·OQ-010·프론트 UI·Keycloak 운영 프로비저닝이 들어갔다. **이미 고쳐졌거나(LEARNINGS) 명시적으로 유보된(OQ) 항목은 새 결함으로 재보고하지 않았다** — 단, 그 수정이 불완전하거나 회귀를 낳은 경우는 예외다.

> **읽는 법.** `[실증]`=실제로 재현/실행해 확인. `[코드]`=코드를 직접 읽어 확정(실행 재현은 안 함). `[코드·Nfinder]`=서로 다른 finder N개가 독립적으로 같은 결함을 확인. 모든 발견은 정확한 `file:line`을 인용한다.

---

## 요약

**직전 리뷰의 R-5(감사 doFinally) 수정이 검색 단계 실패 경로를 닫지 못했다** — 검색/임베딩이 실패하면 사용자에겐 error 이벤트가 가지만 **감사 로그는 0건**이다. 이것을 살아 있는 스택으로 재현했다(성공 채팅은 감사 +1, litellm 중지 후 채팅은 +0). 감사(governance 상위 밴드) 결함이라 이번 리뷰의 헤드라인이다.

그 밖에 **운영 배포에서 프론트 컨테이너가 Keycloak 백채널에 도달하지 못해 로그인이 완료되지 않는** 토폴로지 결함(OQ-012의 급소), **동시 재색인 경쟁으로 생기는 유령 문서**, **활성 세션 중 토큰 미갱신**, 프론트 경쟁·입력 검증 공백을 확인했다.

| # | 심각도 | 밴드 | 상태 | 위치 | 한 줄 |
|---|---|---|---|---|---|
| F1 | **HIGH** | 감사 | [실증]+[코드·3finder] | `ChatService.java:100-129` | 검색 단계 실패·조기 취소 시 감사 로그 0건 |
| B1 | **HIGH** | 보안/운영 | [코드] | `auth.ts:21`·`token.ts:63`·`compose:179` | 운영 프론트 컨테이너가 Keycloak 백채널 도달 불가 → 로그인 미완 |
| F2 | MEDIUM | 데이터 | [코드·검증2] | `IndexingService.java:184` | 동시 same-docKey 색인이 서로 조각 삭제 → 유령 문서 |
| B2 | MEDIUM | 보안 | [코드] | `realm-export.json:4` | 운영 realm `sslRequired:"none"` — HTTPS 미강제 |
| F3 | MEDIUM | 프론트/인증 | [코드] | `core.ts:29`·`auth.ts:66` | 활성 세션 중 토큰 갱신 미동작 → 5분 뒤 401 |
| F4 | MEDIUM | 프론트 | [코드] | `Chat.tsx:110-125` | 이력 로드 effect가 진행 중 메시지/스트림 덮어씀 |
| F5 | LOW~MED | 정확성 | [실증]+[코드] | `ChatController.java:59,90,100` | null 질문 → 새 세션 경로 raw HTTP 500 |
| B3 | LOW~MED | 운영 | [코드] | `Dockerfile:42`·compose | 컨테이너 메모리 제한 부재 + JVM 75% → 다중 JVM OOM |
| F6 | LOW | 정확성 | [코드] | `ChatController.java:100` | 세션 제목 `substring(0,60)`이 서로게이트 쌍 분리 |
| F7 | LOW | 프론트/BFF | [코드] | `chat/stream/route.ts:36,52` | messages 누락 요청에 라우트 500 |

---

## F1 · 검색 단계 실패·조기 취소 시 감사 로그 0건 — HIGH [실증]+[코드·3finder]

- **위치:** `backend/.../chat/ChatService.java:100-129`
- **참조:** S5 · REQ-AUDIT('채팅 1회 → 감사 1건') · R-5(2026-07-11 LEARNINGS) · S8-3

**근본 원인.** `stream()`은 `Blocking.call(() -> searchService.search(...))`(:100) → `.flatMapMany(sources -> Flux.concat(...).doOnComplete(persist).doFinally(recordAudit))`(:101-128) → `.onErrorResume(error 이벤트)`(:129) 구조다. 감사 기록 `doFinally(:119)`는 **flatMapMany 매퍼 람다 안쪽**에서 조립되는 Flux에 붙는다. Reactor에서 소스 Mono(검색)가 onError를 내면 매퍼 함수 자체가 호출되지 않아, 그 안의 Flux와 `doFinally`가 **구독조차 되지 않는다.** 오류는 곧장 `onErrorResume`으로 가 `ChatEvent.Error`만 방출하고 `recordAudit`는 한 번도 실행되지 않는다. 반면 LLM(답변) 단계 실패는 이미 구독된 Flux 안에서 나므로 `doFinally`가 `ON_ERROR`로 발화해 `outcome=ERROR`로 감사가 남는다 — **실패 위치에 따라 감사 유무가 갈린다.** 소스 확정 이전의 **취소**도 같은 경로라 `CANCELLED` 감사가 남지 않는다(R-5 취소 테스트가 "sources 이후 취소"로 우회했던 것이 방증). 주석 `:116-118`은 "doFinally는 complete·cancel·error 모든 종료에서 정확히 한 번 돈다"고 **정반대를 단언**한다.

**실패 시나리오.** LiteLLM 임베딩 게이트웨이 또는 ES 장애·타임아웃(REL-1, S8-3의 일급 실패 경로)으로 `searchService.search`(SearchService.java:51-52)가 예외 → 사용자는 `event:error`를 정상 수신하지만 `audit_log`에 아무 행도 생기지 않는다. V3가 도입한 `ERROR` outcome과 REQ-AUDIT 불변식이 무너진다. 서버 로그(`log.error`)만 남는데, OQ-006이 명시했듯 서버 로그는 보존 정책이 없어 장기 감사 근거가 못 된다.

**[실증]** (dev 스택, dev-user 토큰):
- 정상 채팅(litellm up) → `event:sources`→`text…`→`done`, `audit_log` **9 → 10 (+1)**.
- litellm 중지 후 동일 채팅 → `event:error`("요청을 처리하지 못했습니다. 추적 ID: …")만, `audit_log` **10 → 10 (+0)**.
- 사용자 대면 오류 처리(S8-3 클린 error)는 정상 — **누락되는 것은 감사뿐**이다.

**수정 방향(별도 승인).** 감사 기록을 검색 실패·조기 취소를 포함하는 종료 지점으로 끌어올린다(예: 감사 여부를 `AtomicBoolean`으로 추적해 `onErrorResume`/취소 경로에서 미기록 시 `recordAudit(ERROR/CANCELLED)`, 또는 검색 실패를 "빈 sources"로 정규화해 같은 doFinally를 통과). LLM 단계의 기존 ERROR 기록과 **이중 기록을 피할 것.** AUDIT 사안이므로 검색 실패 감사 1건을 단언하는 테스트를 뮤테이션으로 검증해 추가한다.

---

## B1 · 운영 프론트 컨테이너가 Keycloak 백채널에 도달하지 못해 로그인 미완 — HIGH [코드]

- **위치:** `frontend/auth.ts:21` · `frontend/lib/token.ts:63` · `docker-compose.yml:179`
- **참조:** REL-5 · S25 · **OQ-012**(운영 브라우저 로그인 미검증 — 이 결함의 정확한 급소)

**근본 원인.** 운영 compose는 프론트 컨테이너에 코어는 **내부 서비스명**(`CORE_BASE_URL: http://backend:8080`)으로 주면서 Keycloak은 **외부 주소 하나**(`KEYCLOAK_URL: ${KEYCLOAK_HOSTNAME:-http://localhost:8081}`, :179)만 준다. BFF는 이 값을 OIDC `issuer`로 쓰고(`auth.ts:21`), Authorization Code의 **code→token 교환**과 **refresh**를 그 issuer의 토큰 엔드포인트로 **서버측(프론트 컨테이너 안)** 에서 호출한다(`token.ts:63` `fetch(\`${issuer}/protocol/openid-connect/token\`)`). 컨테이너 내부에서 `localhost:8081`은 Keycloak이 아니라 프론트 자신이므로 백채널이 도달하지 못한다. 브라우저 리다이렉트만 되고 서버측 토큰 교환은 실패한다. 백엔드는 JWKS를 내부명 `keycloak:8080`으로 부르는데(`compose:115`) 프론트는 **내부/외부 issuer 분리가 없다.**

**실패 시나리오.** 운영자가 문서대로 `docker compose -f docker-compose.yml up -d`로 띄우면(기본 `KEYCLOAK_HOSTNAME=http://localhost:8081`) 브라우저 로그인 → code 리다이렉트까지는 되지만 프론트 컨테이너의 code→token POST가 자기 자신(localhost)으로 나가 연결 거부 → 로그인 영구 미완료. 실제 외부 호스트명을 넣더라도 그 URL이 **프론트 컨테이너에서 라우팅 가능**해야 하는 요구가 문서화·검증되지 않았다.

**주의.** dev(소스 실행, 컨테이너 아님)에서는 `localhost:8081`이 실제 Keycloak이라 재현되지 않는다 → 운영 컨테이너 토폴로지 사안이라 [코드]로 남긴다. **OQ-012의 "운영 브라우저 로그인" 검증에서 즉시 드러날 항목**이며, 방금 추가한 `bootstrap-prod.sh`가 클라이언트를 프로비저닝해도 이 백채널 도달성은 별개다.

**수정 방향.** 프론트에 브라우저용(외부) issuer와 백채널용(내부) 엔드포인트를 분리 제공하거나(예: discovery/token을 `http://keycloak:8080/realms/llmhub`로), 외부 `KEYCLOAK_HOSTNAME`이 프론트 컨테이너에서 도달 가능해야 함을 요구로 명시·검증한다.

---

## F2 · 동시 same-docKey 색인이 서로의 조각을 삭제 → 검색불가 유령 문서 — MEDIUM [코드]

- **위치:** `backend/.../idx/service/IndexingService.java:166-184` · `ElasticsearchChunkRepository.deleteStaleChunks`
- **참조:** S17('데이터 유실 없음') · R-3/R-11(단일스레드 변형만 수정됨)

**근본 원인.** `run()`에 docKey 단위 직렬화·락이 없다. `documentId = DocumentId.of(docKey)`(:166)는 결정적이라 같은 docKey 두 요청이 같은 `D`를 공유하고, `indexingRunId = UUID.randomUUID()`(:167)는 run마다 다르다. ES `_id = D:runId:location`이라 A·B 조각은 공존하고, `deleteStaleChunks(D, runId)`(:184)는 `document_id=D AND indexing_run_id != runId`를 전부 지운다. 인터리빙 `A.indexAll → B.indexAll → A.deleteStale(=B 삭제) → B.deleteStale(=A 삭제)`이면 ES 조각 0개가 된다. 이후 두 run 모두 현재 model/version으로 PG upsert(:193)하므로 `staleDocuments()`(model/version 불일치로만 탐색)에 안 잡힌다 → **검색 불가이면서 복구 트리거 없는 영구 유령.** 주석 `:190-192`는 "R-3 유령 방지"를 단언하지만 그것은 단일스레드 ES-실패 변형만 덮는다.

**실패 시나리오.** 관리자가 같은 doc_key를 동시에 재업로드/재색인(임베딩 read-timeout 120s로 창이 넓다). 둘 다 200을 반환한다. IndexController에 큐·락이 없다(`IndexController.java:31` "진행률·큐 없음").

**수정 방향.** docKey(또는 documentId) 단위로 색인을 직렬화한다(PG advisory lock 또는 낙관/비관 락). 동시 색인이 구조적으로 배제되면 상호 삭제도 사라진다.

---

## B2 · 운영 realm `sslRequired:"none"` — HTTPS 미강제 — MEDIUM [코드]

- **위치:** `docker/keycloak/realm-export.json:4` · `docker-compose.yml:80-81,86-87`
- **참조:** SEC-1 · S25

**근본 원인.** dev·운영 공통으로 import되는 realm에 `"sslRequired": "none"`이 있어 Keycloak이 어떤 요청에도 HTTPS를 강제/리다이렉트하지 않는다. Keycloak 안전 기본값은 `external`(외부 주소만 HTTPS 요구, 사설망 HTTP 허용)인데 가장 느슨한 `none`으로 낮췄다. 운영 compose는 이미 `KC_PROXY_HEADERS: xforwarded`(:81)를 켜므로, `external`이면 프록시가 `X-Forwarded-Proto=https`를 넘길 때 운영은 통과하고 dev의 localhost(사설망)는 HTTP가 허용되어 **한 파일로 두 환경을 만족**한다. dev는 어차피 사설 주소라 `none`이 주는 이득이 없다.

**실패 시나리오.** 리버스 프록시를 아직 안 붙인 채(문서상 권장이나 강제 아님) 기본 운영 명령으로 스택을 올리면, 사용자가 `http://<host>:8081` 로그인 폼에 자격증명을 평문 제출하고 Keycloak이 그대로 수락한다. 같은 네트워크 관찰자가 자격증명·베어러를 가로챌 수 있다. 프록시가 있어도 `sslRequired=none`이면 Keycloak 자체의 X-Forwarded-Proto 방어선이 사라진다(심층방어 상실).

**수정 방향.** `sslRequired`를 `external`로. 커밋 파일 하나로 dev·운영 모두 적용.

---

## F3 · 활성 세션 중 토큰 갱신 미동작 → 5분 뒤 401 — MEDIUM [코드]

- **위치:** `frontend/lib/core.ts:25-36` · `frontend/auth.ts:53-72`
- **참조:** S25 · OQ-008(갱신 도입) · OQ-012

**근본 원인.** 토큰 갱신은 `auth.ts`의 jwt 콜백(:66-71)에만 있고, 이는 `auth()`(서버 렌더)와 `/api/auth/*` 핸들러에서만 실행된다. 그런데 BFF의 실제 데이터 경로 `bearerToken()`(core.ts:29)은 `getToken`(next-auth/jwt)을 쓴다 — 이 유틸은 JWE 쿠키를 **복호화만** 하고 콜백을 실행하지 않아 갱신이 없다. 채팅·세션 화면은 `'use client'` SPA라 활성 사용 중 서버 렌더가 다시 일어나지 않고, `middleware.ts`도 `SessionProvider`/`/api/auth/session` 폴링도 없다(grep 확인). 따라서 **로그인 후 클라이언트에서 계속 쓰는 동안 토큰을 갱신할 경로가 하나도 없다.** access token 수명 5분 뒤 다음 요청이 만료 베어러를 코어로 보내 401. OQ-008이 도입한 갱신이 핫패스에서 사실상 무효다.

**주의.** OQ-012의 "토큰 만료(5분)·refresh·401→로그인" 브라우저 검증 항목이 정확히 이 갭을 드러낼 것이다. 검증 전에 근본 경로를 확정할 것.

**수정 방향.** 만료 임박 시 갱신하고 `Set-Cookie`를 쓸 수 있는 middleware, 또는 클라이언트 `SessionProvider`로 `/api/auth/session` 주기 폴링.

---

## F4 · 이력 로드 effect가 진행 중 메시지/스트림을 덮어씀 — MEDIUM [코드]

- **위치:** `frontend/components/Chat.tsx:110-125`
- **참조:** S2

**근본 원인.** 이력 로드 effect는 세션 선택 시 `/messages`를 가져와 `setMessages(toUiMessages(history))`로 목록을 **통째 교체**한다. 이를 막는 가드는 `cancelled` 하나뿐인데, 이 플래그는 cleanup(세션 전환·언마운트)에서만 참이 된다. **같은 세션에서** 사용자가 새 턴을 시작(sendMessage)해도 `cancelled`는 거짓이라, in-flight fetch가 send 이후 resolve되면 방금 보낸 사용자 메시지와 스트리밍 응답을 (그 턴을 포함하지 않는) 이력 스냅샷으로 덮어쓴다.

**실패 시나리오.** 세션 선택(또는 Cmd+K 새 세션) 직후 이력 fetch가 in-flight인 동안 곧바로 질문 전송 → 이력 응답 도착 시 질문·스트리밍 응답이 UI에서 사라진다(코어는 계속 처리). 창은 좁지만 이력이 큰 세션·빠른 키 조작에서 도달 가능.

**수정 방향.** 이력 적용 전에 "그 사이 새 턴이 시작됐는지"를 확인(로드 시작 시 스냅샷과 비교, 또는 `status`가 idle이 아니면 `setMessages` 생략). 세션 전환뿐 아니라 새 턴 시작도 이력 덮어쓰기를 취소해야 한다.

---

## F5 · null 질문 → 새 세션 경로 raw HTTP 500 — LOW~MEDIUM [실증]+[코드]

- **위치:** `backend/.../chat/api/ChatController.java:59,90,100`
- **참조:** S8-3(깨끗한 실패: error 이벤트로 종료)

**근본 원인.** 길이 가드(:59)가 `request.question() != null && …` 형태라 null 질문을 통과시킨다. `ChatStreamRequest`는 검증 애노테이션 없는 순수 record이고 chat 패키지에 `@Valid`/`@ControllerAdvice`도 없다. `sessionId`가 null이면 `sessionOf`(:90)가 `titleOf(request.question())`(:100)에서 `question.length()`로 NPE를 낸다. 이 NPE는 컨트롤러의 `Blocking.call`(:69) 안에서 나는데 **컨트롤러 Flux에는 onErrorResume이 없다**(ChatService의 :129 바깥). 반면 sessionId가 주어진 경로는 null 질문이 ChatService 내부에서 나 :129가 클린 error 이벤트로 닫는다 — **두 경로의 실패 처리가 불일치**한다.

**[실증].** dev-user 토큰으로 `POST /api/chat/stream` 바디 `{"sessionId":null,"question":null}` 및 `{}` → **HTTP 500**(`{"status":400/500,"error":...}` JSON, SSE `error`/`done` 프레임 없음). 기존-세션 경로의 클린 error(200)와 대비.

**수정 방향.** 진입부에서 question이 null/blank면 명시적 400으로 거부하거나, 컨트롤러 Flux에 onErrorResume을 걸어 두 경로가 동일하게 error 이벤트로 닫히게 통일.

---

## B3 · 컨테이너 메모리 제한 부재 + JVM `MaxRAMPercentage=75` → 다중 JVM OOM — LOW~MEDIUM [코드]

- **위치:** `backend/Dockerfile:42` · `docker-compose.yml`(전 서비스 `mem_limit`/`deploy.resources` 없음)
- **참조:** REL-5 · S26/S27 · PERF-1

**근본 원인.** backend가 `-XX:MaxRAMPercentage=75`(:42)로 힙을 잡는데 compose 어느 서비스에도 cgroup 메모리 제한이 없다. 제한이 없으면 JVM은 컨테이너 몫이 아니라 **호스트 전체 RAM의 75%**를 최대 힙으로 삼는다. Keycloak 컨테이너도 명시 힙이 없어 자체 기본으로 auto-size한다. 회사별 단일 호스트 설치 토폴로지(postgres·ES(-Xmx1g)·keycloak·litellm·backend·frontend 동거)에서 두 JVM(backend+keycloak)이 각각 호스트 RAM의 대부분을 상한으로 잡아 총합이 물리 메모리를 초과한다.

**실패 시나리오.** 8~16GB 단일 호스트에서 부하가 오르면 두 JVM 힙 + ES 1g + PG 버퍼가 물리 초과 → 커널 OOM-killer가 컨테이너(대개 backend/ES)를 죽인다. `restart:` 정책도 없어(아래 노트) 그대로 정지. 호스트가 크면 안 드러나 배포 규모에 따라 산발.

**수정 방향.** 각 서비스에 명시적 메모리 제한(`deploy.resources.limits.memory`/`mem_limit`)을 두고 JVM 힙을 그 제한 기준으로 잡히게 한다. 단일 호스트 설치 프로파일의 총 메모리 예산을 문서화한다.

---

## F6 · 세션 제목 `substring(0,60)`이 서로게이트 쌍 분리 — LOW [코드]

- **위치:** `backend/.../chat/api/ChatController.java:100` · **참조:** S2

`titleOf`가 UTF-16 코드유닛 60에서 자른다. 인덱스 59가 high surrogate, 60이 low surrogate인 경우(60번째 문자에 이모지·보조평면 한자가 걸침) 고립 high surrogate가 제목에 남아 `chat_session.title`에 깨진 문자열이 저장되고 사이드바에 U+FFFD로 표시된다. `TokenChunkingStrategy`는 코드포인트 경계를 구조적으로 지키는데(splitByCodePoints) 제목 경로는 아니다. 표시 결함(데이터 손실 아님). **수정:** `Character.offsetByCodePoints` 또는 `codePoints().limit(60)`로 코드포인트 경계 절단.

---

## F7 · messages 누락 요청에 채팅 라우트 500 — LOW [코드]

- **위치:** `frontend/app/api/chat/stream/route.ts:36,52` · **참조:** —

POST 핸들러가 `lastUserText(body.messages)`(:36)를 호출하고 `lastUserText`(:52)는 `[...messages].reverse()`로 시작한다. `body.messages`가 undefined면 스프레드에서 `TypeError`가 나 핸들러가 이를 잡지 않아 Next가 500을 낸다. 입력 형태 검증 부재. 정상 `useChat` 경로는 항상 messages를 보내므로 UI로는 미도달(인가는 이미 통과, 데이터 유출 없음). **수정:** `body.messages` 배열 검증 후 400 반환, 최소한 `lastUserText`에 비배열 방어.

---

## 기각·메모 (추측 배제 근거)

적대적 검증에서 **반증되어 채택하지 않은** 항목과, 관찰은 맞지만 결함으로 승격하지 않은 노트다.

- **ES 슈퍼유저 비번이 헬스체크 명령줄에 노출** (`compose:60`) — **기각.** 같은 `ES_PASSWORD`가 헬스체크와 무관하게 `elasticsearch`/`backend` 컨테이너 env(`compose:52,118`)로 이미 `docker inspect`·`/proc/1/environ`에 영구 노출된다(ES 슈퍼유저 부트스트랩에 env가 필수라 제거 불가). 헬스체크는 증분 공격표면 0 → 근본원인("헬스체크가 노출을 도입") 성립 안 함.
- **컨텍스트 예산 절단이 고아 assistant 메시지 생성** (`RecentTurnsContextAssembler.java:53`) — **기각.** 결함이 아니라 `RecentTurnsContextAssemblerTest.java:156-166`이 명시적으로 못박은 의도된 설계.
- **부하 격리가 테스트 전용 풀 크기 64에 의존** (`build.gradle.kts:101`) — **기각.** 격리는 `Blocking`의 `subscribeOn(boundedElastic())`로 **구조적**이며 풀 크기와 무관하다. 64는 `LoadIsolationTest`의 결정성용일 뿐 격리 보장이 아니다.
- **restart 정책 부재** (`compose` 전 서비스) — **노트(미승격).** 사실이나(전 서비스 `restart:` 없음) 배포 운영 선택 사안. B3(메모리)와 함께 운영 하드닝으로 묶어 재검토 권장.
- **턴 내 질문·답변이 동일 `created_at`** (`PostgresChatHistoryRepository.java:79`) — **노트(잠재).** 순번 컬럼이 없어 tie-break가 스키마 계약에 없다. 현재는 append-only·무갱신이라 삽입 순서가 유지돼 표면화되지 않음. 향후 보조 인덱스·실행계획 변화 시 답변→질문 역순 위험 → 순번 컬럼 권장.
- **`LinearCombinationMergerTest`의 fail-closed 단언이 문자열 contains뿐** (`:104`) — **노트(테스트 품질).** 빈 태그 fail-open 뮤테이션 시에도 쿼리 문자열에 `access_tags`가 남아 유닛 테스트가 green 유지. 단, 빈 태그 fail-closed 자체는 상위 게이트(AccessTagGate)에서 강제되므로 회귀 위험은 제한적. terms 비어 있음을 단언하도록 강화 권장.

---

## 총평

직전 리뷰의 헤드라인("초록불이 안전을 뜻하지 않는다")이 이번에도 반복됐다: **F1은 R-5 수정이 닫았다고 주석까지 단 경로가 실제로는 열려 있었고**, 그 사실을 검증하는 테스트가 없었다(검색 실패 감사 1건을 단언하는 테스트 부재). F2·B2의 주석 역시 실제보다 넓은 보장을 단언한다. 상위 발견은 대부분 **실패·동시성·운영 토폴로지** 경로에 몰려 있다 — 정상 경로 테스트는 초록불이지만 그 경로들은 강제되지 않는다.

**다음 단계(별도 승인):** F1·B1을 최우선으로, 각 발견을 TDD(실패 재현 테스트 → 수정)로 처리. B1·F3은 OQ-012 브라우저 검증과 함께 확인.
