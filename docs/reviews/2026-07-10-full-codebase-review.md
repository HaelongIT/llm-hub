# 코드리뷰 · 2026-07-10 · 전체 코드베이스

- **범위:** `backend/src/main`, `backend/src/test`, `frontend/`, `docker-compose.yml`, `application.yml`, 마이그레이션
- **기준 커밋:** `1bd59e1`
- **방법:** 6개 관점(동시성/리액티브, 보안/인가, 데이터 정합성, 프론트/BFF, 테스트 품질, 운영/설정)을 병렬 리뷰한 뒤, **주요 발견은 사람이 직접 실험으로 재검증**했다. 추측만으로 남긴 항목은 `[미검증]`으로 표시한다.
- **당시 상태:** 백엔드 225 테스트 / 프론트 44 테스트 green

> **읽는 법.** `[실증]`은 실제로 재현하거나 실행해 확인한 것이다. `[코드]`는 코드를 읽어 확정했으나 실행 재현은 하지 않은 것. `[미검증]`은 리뷰어의 판단이며 확인이 필요하다.

---

## 요약

**전체 테스트가 초록불이라는 사실이 안전을 뜻하지 않는다는 것이 이 리뷰의 결론이다.**

`S17`이 "역순 금지"라고 못박은 색인 교체 순서를 **의도적으로 뒤집었는데 225개 테스트가 전부 통과했다**. 그 순서를 지킨다고 주장하는 유일한 테스트가 무력했다. 이것이 이번 리뷰의 가장 중요한 발견이다.

그 밖에 운영을 멈출 수 있는 결함 2건(외부 호출 무한 대기, 로그 무한 증가), 데이터 정합성 결함 1건(유령 문서), 방금 내가 넣은 인증 버그 1건(영구 강제 재로그인)을 실증했다.

| # | 심각도 | 상태 | 한 줄 |
|---|---|---|---|
| R-1 | CRITICAL | `[실증]` | S17 교체 순서를 뒤집어도 전체 테스트가 통과한다 |
| R-2 | CRITICAL | `[실증]` | LiteLLM·ES 호출에 타임아웃이 없어 무한 대기한다 (REL-1 정면 위반) |
| R-3 | HIGH | `[실증]` | ES 쓰기 실패 시 검색 불가·재색인 대상에도 안 뜨는 유령 문서가 남는다 |
| R-4 | HIGH | `[실증]` | 토큰 갱신 성공이 `error`를 지우지 않아 영구 강제 재로그인에 갇힌다 |
| R-5 | HIGH | `[실증]` | 클라이언트가 스트림을 끊으면 감사 로그가 남지 않는다 |
| R-6 | HIGH | `[실증]` | 운영 compose가 설정 키 7개를 백엔드에 전달하지 않는다 (REL-4 붕괴) |
| R-7 | HIGH | `[코드]` | 로그 회전·크기 제한이 없다 → 디스크 고갈 |
| R-8 | HIGH | `[코드]` | access token이 `/api/auth/session`으로 브라우저 JS에 노출된다 |
| R-9 | HIGH | `[코드]` | 동시 요청이 회전 refresh token으로 경쟁 갱신 → 강제 로그아웃 |
| R-10 | MEDIUM | `[실증]` | `chat_session.updated_at`이 영원히 갱신되지 않는다 |
| R-11 | MEDIUM | `[코드]` | bulk 부분 실패 시 반쪽 색인이 남고 보상하지 않는다 |
| R-12 | MEDIUM | `[코드]` | user/assistant 메시지가 별도 트랜잭션 2개 |
| R-13 | MEDIUM | `[코드]` | 업로드 크기 상한이 전체를 힙에 올린 뒤 검사된다 |
| R-14 | MEDIUM | `[코드]` | BFF가 라우트가 아니라 그 **복제본**을 테스트한다 |
| R-15 | MEDIUM | `[실증]` | `RATE_LIMIT_ENABLED`는 아무도 읽지 않는 죽은 설정이다 |
| R-16 | MEDIUM | `[코드]` | JWT의 issuer·audience를 검증하지 않는다 |
| R-17 | MEDIUM | `[코드]` | 클라이언트가 끊어도 코어→LLM 스트림이 계속 돈다 |
| R-18 | LOW | 여러 건 | 아래 §L 참조 |

---

## CRITICAL

### R-1. S17 교체 순서를 뒤집어도 전체 테스트가 통과한다 `[실증]`

`docs/02` S17과 `docs/03`은 **"신버전 색인 완료 → 그다음 구버전 삭제"** 를 절대 순서로 못박는다. 역순이면 색인 도중 장애 시 문서가 통째로 증발한다. `IndexingService.java:167,174`는 그 순서를 지키고, 코드 주석은 *"순서가 전부다"* 라고 적혀 있다.

그 순서를 강제한다고 주장하는 테스트는 `IndexingServiceTest.삭제는_색인_이후에_일어난다:152-162` 하나뿐이다.

**실험:** `IndexingService`의 `indexAll`과 `deleteStaleChunks` 호출 순서를 뒤집었다(= 문서 증발 버그를 심었다).

```
./gradlew test  →  BUILD SUCCESSFUL, 225 tests, 0 failed
```

**왜 못 잡는가:** 그 테스트는 `index()`를 두 번 부르면서 대역의 호출 기록을 사이에 비우지 않는다. 기록은 `[indexAll, deleteStale, indexAll, deleteStale]`이고 단언은 `containsSubsequence("indexAll","deleteStale")`다. 역순이면 기록이 `[deleteStale, indexAll, deleteStale, indexAll]`인데, **1번 `indexAll` 뒤에 2번 `deleteStale`가 있으므로 여전히 참이다.**

다른 테스트도 못 잡는다. 성공 경로 테스트(`재색인하면_구버전_조각이_사라진다`, `V0EndToEndTest`)는 역순이어도 최종 상태가 같다. 실패 주입 테스트는 임베딩 단계에서 터져 이 두 호출에 **도달하지 않는다**.

- **실패 시나리오:** 역순 상태에서 재업로드 → `deleteStaleChunks`가 구버전을 지운 직후 `indexAll`이 ES 장애로 실패 → **문서의 모든 조각이 사라진다.** 원본은 남지만 검색되지 않는다. `S17 × S8-3`이 정확히 막으려던 것.
- **수정:** 첫 `index()` 뒤 `순서.clear()`, 두 번째 호출 결과에 `containsExactly("indexAll","deleteStale")`. (같은 파일 `:329`가 이미 그 패턴을 쓴다.)

### R-2. 외부 호출에 타임아웃이 없다 — 무한 대기 `[실증]`

`REL-1`은 *"무한 대기·행 방지"* 를 요구한다.

**실험:** 연결은 받지만 절대 응답하지 않는 소켓을 세우고 각 클라이언트를 호출했다.

```
LiteLlmEmbeddingClient.embed()  → 10초 경과 후에도 반환 없음
ElasticsearchClient.cluster()   → 10초 경과 후에도 반환 없음
```

`LiteLlmEmbeddingClient.java:23`은 `RestClient.builder()`만 쓰고 타임아웃을 주지 않는다. `ElasticsearchClientFactory.java:22-31`도 마찬가지다. `ChatService`의 SSE `Flux`에는 `.timeout(...)`이 없다.

- **실패 시나리오:** Ollama가 모델 로딩·GPU 점유로 응답 없이 연결만 유지 → 검색 임베딩이 `Blocking.call` 안에서 영구 블로킹 → **boundedElastic 스레드가 영원히 묶인다.** 2코어 운영 VM이면 상한이 20이므로 **동시 채팅 20건이면 풀이 고갈**되고, 그 뒤 색인·세션 조회·이력 저장·감사 저장까지 전부 큐에 쌓인다. `onErrorResume`은 에러가 emit될 때만 돈다 — 아무것도 안 오면 `error`도 `done`도 나가지 않아 사용자 브라우저도 무한 로딩.
- **수정:** `RestClient`에 connect/read 타임아웃, ES transport에 타임아웃, SSE `Flux`에 `.timeout(Duration)` → 초과 시 `ChatEvent.Error`.

---

## HIGH

### R-3. 유령 문서 — 검색 불가인데 재색인 대상에도 없다 `[실증]`

`IndexingService.run()`은 `upsert`(PG 커밋) → `createIndexIfMissing` → `indexAll`(ES) 순이다. `PostgresDocumentRepository.upsert`는 `@Transactional`이라 **반환 시점에 커밋된다.**

**실험:** ES 인덱스에 쓰기만 차단(`index.blocks.write=true`)하고 새 문서를 색인했다. 읽기는 되므로 차원 검사는 통과하고, `indexAll`에서만 실패한다.

```
색인 요청 → HTTP 500
PG:  ghost-doc | model=placeholder-embedding-model   ← 행이 남았다
ES:  조각 수 0
GET /api/index/stale → []                             ← 재색인 대상에도 없다
```

- **왜 stale에 안 뜨는가:** `staleDocuments()`는 `embedding_model != 현재모델`로 찾는데, 이 행은 **현재 모델로 커밋됐다.** 검색도 안 되고 복구 트리거도 없는 문서가 영구히 남는다.
- **더 나쁜 변형:** 임베딩 모델을 바꿔 재색인하다 `indexAll`이 실패하면 PG는 신모델, ES 조각은 구모델이 된다. 불변식 5(`색인 모델 == 검색 모델`) 위반이고, `staleDocuments()`가 못 잡으므로 **운영자는 "재색인 완료"로 오인**한다. 검색 품질만 조용히 붕괴한다(S8-4가 경고한 바로 그것).
- **또 다른 변형:** 태그를 `[public]`→`[restricted]`로 줄인 재업로드가 `indexAll`/`deleteStale`에서 실패하면 구 조각이 `public` 태그로 남는다. 검색 필터는 조각의 사본을 보므로 **권한 없는 사용자에게 노출된다**(불변식 2 위반). `[코드]` — 이 변형은 재현하지 않았다.
- **참고:** ES가 **완전히** 죽은 경우는 안전하다. 차원 검사(`indexedDimensions()`)가 `upsert` 전에 ES를 쳐서 먼저 실패한다. 실측으로 PG 행이 생기지 않음을 확인했다. 위험한 것은 **부분 장애**(쓰기 차단, 디스크 워터마크, 매핑 오류, bulk 거부)다.
- **수정:** ES 신버전 색인 성공을 확인한 뒤 PG 메타데이터를 커밋한다.

### R-4. 토큰 갱신 성공이 `error`를 지우지 않는다 — 영구 강제 재로그인 `[실증]`

**직전 커밋(`345b2b2`)에서 내가 넣은 버그다.**

`lib/token.ts:74-79`의 성공 반환은 `{accessToken, refreshToken, expiresAt}` 3개만 담는다 — `error` 키가 없다. `auth.ts:52`는 `{ ...token, ...refreshed }`로 병합하므로 **이전에 붙은 `error`가 덮이지 않는다.**

**실험:**
```
refreshAccessToken 반환: {"accessToken":"new","refreshToken":"r2","expiresAt":...}
반환에 error 키가 있는가: false
auth.ts 병합 결과 error: RefreshAccessTokenError
★ 갱신에 성공했는데도 error가 남는다
```

- **실패 시나리오:** Keycloak으로의 갱신 요청이 **한 번** 일시적 네트워크 오류를 만난다 → `error` 세팅. 다음 요청에서 갱신을 재시도해 **성공**한다. 그런데 `error`가 그대로 남아 `session.error`가 서고, `proxyToCore`·채팅 라우트·`page.tsx`가 전부 401/로그인 화면으로 간다. 이후 갱신이 계속 성공해도 **재로그인 전까지 영구히 갇힌다.**
- **왜 테스트가 못 잡았나:** `token.test.ts:86`은 격리된 성공 반환의 `error === undefined`만 본다. `auth.ts`의 **병합**은 테스트 대상이 아니다(`npm test`는 `lib/**`만 돈다).
- **수정:** 성공 반환에 `error: undefined`를 명시적으로 포함한다.

### R-5. 클라이언트가 끊으면 감사 로그가 남지 않는다 `[실증]`

`ChatService.java:101`의 `doOnComplete`가 이력·감사 저장을 부르는 **유일한 지점**이다. Reactor에서 **취소는 `doOnComplete`를 실행시키지 않는다.** `doOnCancel`/`doFinally`가 없다.

**실험:** 스트림을 중간에 끊고 대조군과 비교했다.
```
감사 로그 (전):        5
스트림 중도 종료 →    5   ← 늘지 않음
완주 요청      →      6   ← 늘어남
```

- **실패 시나리오:** 사용자가 질문 → `sources`(restricted 문서 근거 포함) + 부분 답변을 수신 → 탭을 닫는다. **근거는 이미 브라우저에 전달되었는데 감사 흔적이 0건이다.** `REQ-AUDIT`은 "채팅 1회 → 감사 1건"을 요구한다.
- **주의:** 이력 미저장은 의도일 수 있다(주석: "성공적으로 끝난 뒤에만"). 그러나 **감사** 공백은 다른 문제다. 이것은 `AUDIT` 태그 사안이라 `docs/08 §D.2`에 따라 보류할 수 없다 — 사람의 결정이 필요하다.
- **수정 후보:** 감사 기록을 `doFinally`로 옮기고 취소된 대화를 "부분"으로 표시한다.

### R-6. 운영 compose가 설정 키 7개를 전달하지 않는다 `[실증]`

`application.yml`이 읽는 환경변수와 `docker-compose.yml`의 backend `environment`를 대조했다.

**전달되지 않는 키:** `SEARCH_ANALYZER`, `ES_INDEX_NAME`, `CHUNK_SIZE_TOKENS`, `MAX_UPLOAD_BYTES`, `MAX_CONTEXT_TOKENS`, `MAX_QUESTION_CHARS`, `SEARCH_BM25_BOOST`, `SEARCH_VECTOR_BOOST`

- **실패 시나리오:** 설치형 고객사 운영자가 `.env`에 `SEARCH_ANALYZER=english`를 넣는다. **아무 일도 일어나지 않는다.** 컨테이너 백엔드는 여전히 nori로 인덱스를 만들고, 에러 없이 BM25 검색 품질만 붕괴한다. 질문 길이 상한·업로드 상한·컨텍스트 예산도 운영에서 튜닝할 수 없다.
- **왜 안 드러났나:** 개발에서는 백엔드를 소스에서 `bootRun`으로 돌려 `.env`를 직접 읽는다. 컨테이너 백엔드는 `profiles: ["app"]`이라 개발 compose에서 제외된다. **REL-4는 개발에서만 참이다.**
- **수정:** backend `environment`에 위 키들을 `KEY: ${KEY:-기본값}` 형태로 추가한다.

### R-7. 로그 회전·크기 제한이 없다 `[코드]`

`application.yml`에는 `logging.pattern.correlation` 한 줄뿐이고 logback 파일이 없다. `docker-compose.yml`의 어느 서비스에도 `logging:` 드라이버 설정이 없다.

- **실패 시나리오:** Docker 기본 `json-file` 드라이버가 크기 제한 없이 로그를 축적 → 수 주 뒤 호스트 디스크 100% → PostgreSQL WAL 기록 실패, ES가 read-only로 전환 → **스택 전체 정지.** 회전이 없어 사후 복구도 수동이다.
- **수정:** compose 각 서비스에 `logging: driver: json-file, options: {max-size: "10m", max-file: "5"}`.

### R-8. access token이 브라우저 JS에 노출된다 `[코드]`

`auth.ts:56`의 `session` 콜백이 `session.accessToken`을 세운다. Auth.js는 session 콜백의 반환 객체를 그대로 `/api/auth/session` 응답으로 내보낸다(`app/api/auth/[...nextauth]/route.ts:3`이 그 핸들러를 등록한다).

- **실패 시나리오:** 로그인한 사용자 브라우저에서 아무 스크립트나 `fetch('/api/auth/session')` → Keycloak 베어러가 JSON에 담겨 돌아온다. XSS가 한 번이라도 성립하면 토큰이 즉시 탈취된다. `docs/01`과 `lib/core.ts`가 세운 **"토큰은 BFF만 쥔다"** 는 불변식이 조용히 깨진다.
- **완화:** 코어는 내부망 전용이라 탈취 토큰으로 브라우저에서 코어를 직접 칠 수는 없다. HTML/번들에 정적으로 박히지도 않는다(`page.tsx`는 토큰을 클라이언트 컴포넌트로 넘기지 않는다). 노출 경로는 런타임 엔드포인트다.
- **미검증 부분:** 실제 `/api/auth/session` 응답 바디를 브라우저 세션으로 확인하지는 않았다.
- **수정:** 세션에 토큰을 싣지 말고, BFF가 필요할 때 서버 전용으로 JWT를 읽는다.

### R-9. 동시 갱신이 회전 refresh token으로 경쟁한다 `[코드]`

Auth.js는 요청마다 `jwt` 콜백을 부르며 **직렬화하지 않는다**. `auth.ts:47-52`에도 in-flight 중복 제거가 없다.

- **실패 시나리오:** access token이 만료된 상태에서 탭 두 개가 동시에 요청 → 둘 다 같은 refresh token으로 갱신 → Keycloak이 회전 → 하나는 성공, 다른 하나는 `invalid_grant` → `error` 세팅. 나중 응답의 `Set-Cookie`가 이기므로, 실패 응답이 이기면 **강제 로그아웃**된다. R-4와 겹치면 영구화된다.
- **미검증:** 실제 동시 요청 재현은 하지 않았다. `@auth/core`가 직렬화하지 않는다는 것은 소스 판독 결과다.
- **수정:** refresh token을 키로 하는 모듈 스코프 in-flight Promise로 중복 갱신을 합친다(다중 레플리카는 여전히 한계).

---

## MEDIUM

### R-10. `chat_session.updated_at`이 영원히 갱신되지 않는다 `[실증]`

`PostgresChatHistoryRepository.append()`는 `chat_message`만 삽입하고 세션을 건드리지 않는다. `ChatSessionEntity.updatedAt`은 생성자에서만 세팅된다. 그런데 `sessionsOf`는 `updated_at desc`로 정렬한다.

```sql
select (created_at = updated_at) from chat_session where 메시지수 > 0;
 → 전부 t (true)
```

- **결과:** 사이드바 "최근 활동순"이 사실상 "생성순"이다. `docs/03`이 "향후 만료 배치 전제"라고 적은 `updated_at`도 활성 세션을 오판한다.

### R-11. bulk 부분 실패 시 반쪽 색인이 남는다 `[코드]`

`ElasticsearchChunkRepository.indexAll:109-110`은 `response.errors()`가 참이면 예외를 던진다. 그러나 **ES bulk는 원자적이지 않다** — 성공한 op는 이미 반영되었다. 예외가 올라가므로 `deleteStaleChunks`도 실행되지 않는다.

- **남는 상태:** 이번 run의 *일부* 조각 + 구 run 전량 공존 → 중복 근거. 신규 색인이면 조각이 누락된 반쪽 문서(R-3와 결합).
- **수정:** 부분 실패 시 이번 `indexingRunId`의 조각을 롤백 삭제한 뒤 예외를 던진다.

### R-12. user/assistant 메시지가 별도 트랜잭션 2개 `[코드]`

`ChatService.persist`는 두 `append`를 한 람다에 넣지만, `PostgresChatHistoryRepository.append`가 메서드 단위 `@Transactional`이라 **독립 트랜잭션 2개**다.

- **실패 시나리오:** user append 커밋 후 assistant append 실패 → **답변 없는 질문**만 이력에 남는다. fire-and-forget이라 로그만 남는다.
- **수정:** `appendTurn(user, assistant, sourcesJson)` 단일 트랜잭션 메서드로 묶는다.

### R-13. 업로드 크기 상한이 전체 적재 후에 검사된다 `[코드]`

`IndexController.java:62`의 `DataBufferUtils.join(file.content())`에 상한 인자가 없고, `UploadValidator`의 크기 검사는 `content.length`가 이미 힙에 올라온 **뒤** 실행된다. `spring.webflux.multipart` 상한도 설정되어 있지 않다.

- **실패 시나리오:** ADMIN이 거대한 파일을 올리면 코어가 전체를 디스크+힙에 적재한 뒤 거부한다 → OOM/디스크 소진. ADMIN 전용이라 노출은 제한적이지만 사고성 업로드 한 건으로 코어가 죽을 수 있다.
- **수정:** `DataBufferUtils.join(flux, maxUploadBytes)` 오버로드로 조인 단계에서 거부한다.

### R-14. BFF가 라우트가 아니라 복제본을 테스트한다 `[코드]`

`lib/bff-stream.test.ts:19-46`의 `translate`는 `route.ts:63`의 `translate`를 손으로 재구현한 **별개 함수**다. 라우트의 것은 export되지 않아 import할 수 없다.

- **갈라진 지점:** 라우트는 읽기 루프를 `try/catch`로 감싸 업스트림이 끊기면 고정 문구로 닫는다(SEC-3). 복제본에는 그 분기가 없다.
- **넣을 수 있는 버그:** `route.ts`의 catch를 지우거나 고정 문구를 `String(e)`로 되돌려 내부 예외를 브라우저에 노출시켜도 **테스트는 통과한다.**
- **수정:** `translate`를 `lib/`로 빼서 라우트와 테스트가 같은 함수를 쓰게 한다.

### R-15. `RATE_LIMIT_ENABLED`는 죽은 설정이다 `[실증]`

`application.yml:75`는 `rate-limit-enabled: false`를 **리터럴로 하드코딩**한다. `.env.example`은 `RATE_LIMIT_ENABLED`를 선언하지만 읽는 코드가 없다.

`SEARCH_ANALYZER`와 같은 부류다. 설정이 있는데 무시된다 — 운영자를 오도한다. (설령 배선되어도 `RateLimitWebFilter`는 v0에서 통과만 하므로 기능적으로는 무해하다.)

### R-16. JWT의 issuer·audience를 검증하지 않는다 `[코드]`

`application.yml:40`은 `jwk-set-uri`만 준다. 그러면 Spring이 만드는 디코더의 기본 검증기는 `JwtTimestampValidator`뿐이다 — issuer·audience 검증이 없다. 권한은 렐름 전역 `realm_access.roles`에서 나온다.

- **실패 시나리오:** 같은 렐름에 저신뢰 클라이언트가 추가되면, 그 클라이언트용으로 발급된 토큰이 백엔드 접근을 그대로 얻는다.
- **현재 위험:** 렐름에 클라이언트가 둘뿐이라 표면이 좁다. `issuer-uri`를 피한 것은 기동 시 네트워크 호출 회피라는 의도된 결정이지만(`LEARNINGS`), audience 검증까지 포기할 이유는 아니다.

### R-17. 클라이언트가 끊어도 코어→LLM 스트림이 계속 돈다 `[코드]`

`route.ts`의 `fetch`에 `AbortSignal`이 없고, 반환 `ReadableStream`에 `cancel()` 핸들러가 없다.

- **실패 시나리오:** 사용자가 탭을 닫아도 BFF는 코어 응답을 끝까지 읽고, 코어는 LLM 토큰 생성을 끝까지 진행한다. 버려진 스트림이 쌓이면 LLM 비용과 커넥션을 낭비한다.

---

## LOW {#L}

| 항목 | 위치 | 요지 |
|---|---|---|
| L-1 | `IndexingService.java:150` | `fileStorage.store()`가 `upsert` 인자로 먼저 평가된다. `upsert` 실패 시 고아 파일. 재업로드는 매번 새 키로 저장하고 구 원본을 지우지 않는다 |
| L-2 | `IndexController.java:96-101` | `toBytes`가 예외를 던지면 조인 버퍼가 해제되지 않는다 |
| L-3 | `ChatService.java:88` | `answer` StringBuilder가 구독이 아니라 호출 단위로 캡처된다. 단일 구독에선 안전, 재구독 시 손상 |
| L-4 | `ChatService.java:139-155` | fire-and-forget `.subscribe()`가 컨텍스트를 전파하지 않아 persist 경로의 MDC traceId가 빈다. 지금은 저장 계층에 로그가 없어 무해 |
| L-5 | `V1__...sql:22` | `document.uploaded_by` FK에 `ON DELETE`가 없어 문서를 올린 사용자 삭제가 차단된다. v0에 사용자 삭제 경로 없음 |
| L-6 | `ElasticsearchChunkRepository.java:164` | 조각 `_id`가 `documentId:runId:location`. 다른 청킹 전략이 중복 location을 내면 덮어써진다 |
| L-7 | `route.ts:82-96` | 루프 종료 후 잔여 버퍼를 파싱하지 않는다. 마지막 프레임이 `\n\n` 없이 끝나면 정상 완료가 오류로 표시된다 |
| L-8 | `Chat.tsx:33` | `useChat`에 `id`가 없어 세션 전환 시 인스턴스가 재사용된다. 이전 세션의 error 배너 잔존, 스트리밍 중 전환하면 메시지 혼입 |
| L-9 | `docker-compose.yml:55-79` | Keycloak에 healthcheck가 없고 frontend가 `service_started`로만 기다린다. 배포 직후 첫 로그인이 실패할 수 있다 |
| L-10 | `backend/Dockerfile:27` | uid를 고정하지 않는다(frontend는 1001로 고정). 리눅스 호스트 바인드마운트 소유권 문제 가능 |
| L-11 | `IndexController.java:55` | 업로드 MIME이 클라이언트 제공값. 내용 스니핑이 없다. ADMIN 전용이라 영향 제한 |
| L-12 | `.env:65` | 작업 트리 `.env`에 실제 `AUTH_SECRET`이 있다. **커밋 대상 파일은 깨끗하다**(`.env.example`·compose·realm-export 전부 placeholder) |

---

## 테스트가 실제로 강제하지 않는 것

R-1과 R-14 외에:

- **`V0EndToEndTest`는 근거가 LLM에 도달하는지 보지 않는다.** 대역 모델이 프롬프트를 무시하고 고정 문자열을 흘린다. `contains("연차휴가는…")`이 매칭되는 곳은 LLM 출력이 아니라 검색이 만든 `event:sources` 프레임이다. 프롬프트 조립에서 근거 주입을 제거해도 통과한다(유닛 테스트가 별도로 잡긴 한다).
- **`ManagementPortMatcher`의 `management == application` 방어 분기가 무검증이다.** 그 분기를 지워도 전부 통과한다. 운영에서 관리 포트를 앱 포트와 같게 오설정하면 `/actuator/health`가 인증 없이 열린다(SEC-1 붕괴).
- **`TraceIdErrorAttributes`에 테스트가 없다.** 에러 본문에 예외 문구를 노출하도록 바꿔도 무검출.
- **`proxyToCore`·`auth.ts` 콜백에 테스트가 없다.** `session.error` 가드를 지워 죽은 베어러를 상류로 보내도 무검출.
- **BlockHound는 JVM 전역·영구 설치**이고 `forkEvery`가 없다. `BlockHoundArmedTest`가 먼저 실행되면 이후 모든 테스트가 무장 상태로 돈다 → 순서 의존. `[미검증]`
- **`LoadIsolationTest`의 1600ms 시간 예산은 코어 수에 의존**한다. 4코어 미만 CI에서 boundedElastic 상한이 32보다 작아 부분 직렬화되면 플래키하다. `[미검증]`
- **`ChatPersistenceTest.장애시에는_저장하지_않는다`** 는 `Thread.sleep(200)` 뒤 비어 있음을 본다. "200ms 안에 저장 안 됨"일 뿐 "영구히 저장 안 됨"이 아니다.

---

## 잘 지켜지고 있는 것 (허위 양성 아님)

- **인가는 기본 닫힘.** `anyExchange().authenticated()`, `/api/index/**`는 ADMIN. 새 엔드포인트는 기본적으로 닫힌다.
- **세션 소유권**이 채팅·조회·삭제 세 경로 모두에 있고 403이 아니라 404를 준다.
- **접근 태그 필터가 kNN pre-filter와 BM25 filter 양쪽**에 걸린다. 빈 태그는 서비스·리포지토리 두 곳에서 막힌다.
- **역할 파싱**이 `realm_access` 없음·타입 혼동에 대해 빈 권한을 반환한다.
- **경로 조작**이 UUID 저장키 + `requireSafeKey`로 이중 차단된다.
- **로그에 질문·응답·문서 원문·토큰이 없다.** 전체 `log.*` 인자를 확인했다.
- **`audit_log`에 FK가 없다**(`information_schema`로 강제).
- **`ensureExists`의 경합**이 `on conflict do nothing` + read로 해결되어 있고 24스레드 테스트가 강제한다.
- **Flyway 실패는 기동을 막는다** — 안전한 실패 방향이다.
- **커밋 대상 파일에 실제 비밀이 없다.**

---

## 우선순위 제안

1. **R-1** — 테스트를 고친다. 지금 이 스위트는 S17 위반을 통과시킨다.
2. **R-2** — 타임아웃. 운영에서 스레드풀 고갈로 전체가 멈출 수 있다.
3. **R-4** — 내가 넣은 버그. 한 줄이면 고쳐진다.
4. **R-3, R-11** — 색인 실패 시 남는 상태. 커밋 순서를 바꾼다.
5. **R-5** — 감사 공백. `AUDIT` 태그라 **사람의 결정이 필요하다**(`docs/08 §D.2`).
6. **R-6, R-7, R-15** — 운영 설정. 배포 전에 반드시.
7. **R-8, R-9, R-16** — 인증 표면.

R-5는 보류할 수 없는 항목이다. 나머지는 순서대로 처리 가능하다.
