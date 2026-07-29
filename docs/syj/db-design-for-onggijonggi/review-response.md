# DB 설계 리뷰 티키타카 — 응답 (라운드 1~4)

옹기종기 팀이 `db-design-for-onggijonggi/` 번들을 자기네 에이전트로 검증한 결과에 대한 응답이다.
**1절**은 llmhub 실제 코드로 검증한 사실(전부 파일:내용 인용 가능), **2절**은 이번 논의에서 새로
제안한 설계(옹기종기 전용, llmhub 코드로 검증된 적 없음)다. 이 둘을 섞어 읽지 않도록 표시를 분리했다.

---

## 1. 1라운드 — 지적 6개 검증 결과

llmhub 실제 마이그레이션(V1~V4)·`IndexingService`·`ChatService`·`PostgresChatHistoryRepository`·
`SecurityConfig`·`ChatSessionJpaRepository`를 직접 열어 대조했다. 추측 없음.

| # | 지적 | 결과 |
|---|---|---|
| 1 | doc 상태 필드 / `idx_run_id` 없음 | 사실이지만 **틀**이 다름 — 아래 상세 |
| 2 | `uploaded_by` FK가 사용자 삭제를 막음 | **틀렸음** — V4에서 이미 `ON DELETE SET NULL`로 해결됨 |
| 3 | `acc_tag` 변경 경로 없음 | **맞음**, llmhub도 미해결 |
| 4 | `chat_msg` 순서가 `created_at`만 의존 | **맞고, 지적보다 심각함** — 아래 상세 |
| 5 | 취소된 응답이 이력에 남을 수 있음 | **틀렸음** — 이미 안전하게 처리됨 |
| 6 | `doc_key` 전역 유니크라 덮어쓰기 위험 | **부분적으로 맞음** — 위협 모델이 admin 전용으로 좁혀짐 |

### 1번 — doc 상태 / idx_run_id: 질문의 틀이 잘못 짜여 있었다

`indexingRunId`는 `IndexingService.run()`이 매 색인마다 `UUID.randomUUID()`로 생성해 **ES 조각에만**
태깅하고 구버전 삭제 판별에만 쓴다. PG엔 원래 없다(grep 0건, 사실).

하지만 llmhub 색인 파이프라인은 팀이 가정한 "다단계 비동기 워커"가 아니다. `IndexController` →
`IndexingService.run()`이 HTTP 요청 하나 안에서 검증→추출→청킹→임베딩→ES색인→PG upsert를 **동기**로
전부 처리한다(주석: "색인은 동기 처리다. 진행률·큐는 없고, 실패하면 에러 응답이다"). 큐도 워커 재개도
없다. `documentRepository.upsert()`가 파이프라인의 맨 마지막이라 그 전에 죽으면 PG 행 자체가
생성/갱신되지 않는다 — "행은 있는데 조각 0개"는 이 구현에서 안 생긴다.

**진짜 위험은 방향이 반대다.** ES 색인 + 구버전 삭제까진 끝났는데 PG upsert 직전에 죽으면, ES엔 이미
`access_tags`까지 박힌 새 조각이 검색 가능한 상태로 올라가 있고 PG엔 그 행이 없거나 옛 메타데이터
그대로인 상태가 남는다. 이건 실제로 미해결 — llmhub 코드에도 `OPEN-QUESTIONS.md`에도 이 케이스를
다루는 자리가 없다. v0가 단일 인스턴스·admin 전용·저빈도라 방치된 것으로 보인다.

→ 옹기종기는 **파이프라인이 실제로 비동기**이므로(2라운드에서 확인) 이 문제가 llmhub보다 훨씬
직접적으로 적용된다. 2절 참고.

### 2번 — `uploaded_by` FK: 이미 해결됨

`V4__document_uploaded_by_on_delete.sql`이 정확히 이 이유로 나중에 추가됐다.

```sql
alter table document drop constraint document_uploaded_by_fkey;
alter table document
    add constraint document_uploaded_by_fkey
        foreign key (uploaded_by) references app_user (id) on delete set null;
```

번들에 V4가 포함돼 있고 `README.md` 용어집에도 `L-5`로 설명돼 있다. **문서 쪽 문제는 남는다** —
`03-data-model.md`의 `document` 표 `uploaded_by` 행에 "ON DELETE SET NULL (L-5)"가 적혀 있지 않아서
스키마 문서만 본 사람에겐 "미정"으로 읽힌다. 다음 갱신 때 추가할 것.

### 3번 — `acc_tag` 변경 경로: 진짜 열린 질문

document의 `access_tags`를 부분 갱신하는 API/서비스가 llmhub 코드베이스에 없다(grep 확인). 유일한
갱신 경로는 재업로드(같은 `doc_key`)뿐이고, 그건 파일 전체를 다시 올려 재임베딩까지 하는 무거운
경로다. "권한만 바뀌었는데 재임베딩까지 해야 하나"는 llmhub도 답을 안 낸 채 방치했다. 옹기종기는
부서 개편·권한 조정이 실제 시나리오라(2라운드 확인) 이걸 먼저 풀어야 한다. 2절 참고.

### 4번 — `chat_msg` 순서: 지적보다 심각함

`PostgresChatHistoryRepository.appendTurn()`은 user 메시지와 assistant 메시지를 **정확히 같은
`Instant now`**로 저장한다(같은 트랜잭션, 변수 재사용):

```java
Instant now = Instant.now(clock);
messages.save(new ChatMessageEntity(UUID.randomUUID(), sessionId, user.role(), user.content(), null, now));
messages.save(new ChatMessageEntity(UUID.randomUUID(), sessionId, assistant.role(), assistant.content(), sourcesJson, now));
```

`seq` 컬럼도 없다. 즉 매 턴마다 두 행이 완전히 동일한 `created_at`을 갖는 게 "가끔 생기는 경우"가
아니라 **설계상 기본값**이다. `ORDER BY created_at ASC`만으로는 동률 순서가 SQL 표준상 보장되지
않는다 — 지금 안 터지는 건 PostgreSQL이 우연히 물리적 삽입 순서로 반환하는 경우가 많아서지, 보장된
동작이 아니다. 옹기종기가 세션 내 단조 증가 `seq`를 지금 넣는 걸 권장한다(2절 체크리스트).

### 5번 — 취소된 응답: 이미 안전함

`ChatService.stream()`에서 이력 저장(`persistHistory`)은 `.doOnComplete()`에만 걸려 있어 스트림이
취소(`SignalType.CANCEL`)되면 절대 호출되지 않는다:

```java
.doOnComplete(() -> {
    log.info("응답 완료 traceId={} answerChars={}", traceId, answer.length());
    persistHistory(sessionId, question, snapshot(answer), sources, traceId);
});
```

잘린 응답은 `chat_message`에 안 남고, `audit_log`에만 `outcome=CANCELLED`로 독립적으로 남는다(별도
`doFinally`, 세션 이력과 무관).

### 6번 — `doc_key` 전역 유니크: 위협 모델이 admin 전용으로 좁혀짐

`/api/index`, `/api/index/**` 전체가 `SecurityConfig`에서 `hasRole("ADMIN")`으로 막혀 있다(경로·메서드
안 가림). 그래서 "일반 사용자가 남의 문서를 노려 교체"하는 경로는 없고, 위협 모델은 **"admin A가
admin B 문서를 실수로/의도로 덮어씀"**으로 좁혀진다. `doc_key`는 시스템 발급이 아니라 **업로드하는
admin이 임의로 정하는 문자열**이다(`IndexController` 주석: "업로드 때 사용자가 정하는 임의의
문자열"). 옹기종기는 admin이 부서별 다수라 이 위협이 실제로 발생한다(2라운드 확인). 2절 참고.

### 작은 것들

- **`adt_log.req_id`**: `jwt.getSubject()`, 즉 keycloak_subject 값 복사. `app_user.id` 아님.
- **`chat_sess.updated_at`**: 앱 레벨. `appendTurn()` 안에서 `session.touch(now)` 호출 → JPA dirty
  checking으로 커밋 시 UPDATE. 트리거 아님.
- **`chat_sess.title`**: 서버가 첫 질문에서 생성(`ChatController.titleOf()`), 60자 넘으면 서로게이트
  쌍 보존하며 자름. `not null`, 클라이언트가 안 보냄.
- **인덱스 정정**: 제안된 `chat_sess(user_id, created_at DESC)`는 실제 쿼리와 안 맞는다. 실제 정렬
  기준은 `updated_at DESC`(`findByUserIdOrderByUpdatedAtDesc`) — 맞는 인덱스는
  `(user_id, updated_at DESC)`. 이 인덱스는 **llmhub 자체에도 없다**(`chat_message(session_id,
  created_at)`만 V2에 있음, `idx_chat_message_session_created`).
- **`emb_mdl` 이름만 기록**: 알려진 사각지대. llmhub `OPEN-QUESTIONS.md`의 **OQ-016**("임베딩 모델이
  같은 이름으로 조용히 바뀔 수 있다 — `bge-m3` 롤링 태그")이 정확히 이 문제이고 아직 미해결.
- **`app_user` 생성 시점**: `keycloak-appuser-provisioning-from-llmhub.md`에 이미 공유함(JIT +
  `on conflict do nothing`). 스키마 문서 자체엔 한 줄도 없는 게 맞음 — 추가 권장.
- **`src_json` 이중화**: 의도된 것. `chat_message.sources_json`은 그 턴에 사용자가 본 근거(UI 재현용),
  `audit_log.sources_json`은 감사용 독립 복사(세션 삭제와 무관하게 남아야 하니 FK 없이 별도 저장).
  `persistHistory`와 `recordAudit`이 같은 `List<Source>`를 각자 `toJson()`으로 따로 직렬화한다.

---

## 2. 2라운드 — 설계 제안 (옹기종기 전용, llmhub에 없는 새 제안)

> 아래는 llmhub 코드로 검증된 사실이 아니라 **이번 논의에서 새로 제안한 설계**다. 1절과 구분해서 읽을 것.

2라운드에서 옹기종기 에이전트가 "색인 동기/비동기"·"admin 구성"에 따라 1번·6번의 답이 갈린다고
역질문했고, 사용자가 직접 확인했다:

- **색인 파이프라인: 전체가 비동기**(파일 변환뿐 아니라 청킹~임베딩~ES색인까지 격리 워커가 처리)
- **`acc_tag` 변경: 실제 운영 시나리오 있음**(부서 개편·권한 조정)
- **admin: 부서별 다수 예정**

이 답을 전제로 제안한다.

### 2-1. doc 상태 추적 — `status` + `pending_idx_run_id`/`cur_idx_run_id`

> **(4라운드 정정)** 원래 `cur_idx_run_id` 하나만 제안했으나, 이 필드를 런 시작 시점에 덮어쓰면
> "확정된 런" 포인터가 파괴되는 결함이 있었다. 아래는 정정된 버전이다 — 상세 사유는 §5-2 참고.

llmhub의 "동기라 상태 불필요" 논리는 안 넘어간다. 최소 필드를 제안한다.

- **`document.status`**: `PENDING` / `PROCESSING` / `READY` / `FAILED`.
- **`document.status_at`**: 상태 진입 시각. 멈춘 PROCESSING 감지용(§5-2).
- **`document.pending_idx_run_id`**: 지금 진행 중인(아직 완료 안 된) 색인 실행의 식별자. 런 시작 시
  즉시 기록.
- **`document.cur_idx_run_id`**: **마지막으로 성공 확정된** 색인 실행의 식별자. 런이 성공했을 때만
  갱신한다 — 진행 중에는 절대 건드리지 않는다. 정리·재검증 배치가 "지금 살아 있어야 할 조각"을 판단할
  유일한 근거이므로, 진행 중인(아직 안 끝난) run_id로 이 값을 덮으면 아직 서비스 중인 구버전 조각이
  "구버전"으로 오판되어 삭제될 수 있다.
- **`document.last_error`**: 마지막 실패 사유. `FAILED` 상태와 함께 채운다.

**기록 순서는 llmhub과 반대여야 한다.** llmhub은 PG upsert를 맨 마지막에 해서 "성공한 것만 보이게"
했는데, 그건 동기 요청-응답이라 가능했다. 비동기 워커는 **런이 시작될 때 먼저 PG에 `PROCESSING` +
`pending_idx_run_id`를 기록**해야 워커가 죽었을 때 "이 문서가 멈춰 있다"는 게 보인다(§5-2 타임아웃과
함께 동작). 안 그러면 1절 1번에서 llmhub이 걱정 안 한(못 한) "아무 흔적도 없이 그냥 멈춤" 상태가
생긴다.

순서: 런 시작 시 PG `PROCESSING`+`status_at`+`pending_idx_run_id` 기록 → 추출~임베딩 → ES 색인 → ES
구버전 삭제(llmhub의 S17과 같은 원리, `deleteStaleChunks(documentId, indexingRunId)` 참고) → 성공하면
PG `READY`+`cur_idx_run_id`를 `pending_idx_run_id` 값으로 확정, 실패/타임아웃이면 `FAILED`+
`last_error`(이때 `cur_idx_run_id`는 **건드리지 않는다** — 이전 성공 런을 계속 가리켜야 정리 배치가
안전하다).

PROCESSING 중 검색 동작(재업로드 시 구버전 조각을 계속 보여줄지)은 **허용으로 확정**했다 — §5-2 참고.

**개별 시도 이력이 필요하면** `document`에 컬럼을 늘리는 대신, llmhub의 `audit_log`와 같은 패턴으로
**별도 `index_run` 테이블**(`id`, `document_id`, `status`, `error_message`, `started_at`,
`finished_at`)을 두는 방법도 있다. 실패 원인 추적·재시도 판단에 좋고, `document`는 "현재 확정 상태"만
가벼운 필드로 유지할 수 있다. 관측성 요구 수준에 따라 결정할 것(§4 질문).

### 2-2. `acc_tag` 갱신 — "ES 먼저, PG 나중" 규칙

> **(4라운드 정정)** 아래 규칙은 **태그 갱신 자체가 단독으로 일어날 때만** 안전하다. 색인 워커는
> 태그를 복사해 오려고 PG의 `access_tags`를 읽으므로(S18 — PG가 원천), 갱신과 **동시에 재색인이
> 도는 경우**엔 이 순서만으로 막지 못한다. 원래 "PG는 뒤늦게 따라와도 무해하다"고 무조건적으로
> 적었던 건 틀렸다 — 정정하고 실제 안전망(`acc_tag_ver`)은 §5-1에 있다.

> **항상 ES 먼저, PG 나중.** 태그가 좁아지든 넓어지든 이 순서 하나면 **단독 갱신 기준으로는** 안전하다.

- **좁히는 방향(권한 회수)**: ES 조각의 `access_tags`를 먼저 `update_by_query`로 갱신 → 노출이 즉시
  닫힌다. **검색 시점에는** PG를 안 쓰므로(S18 — ES 조각이 실제 필터링 원천) 그 순간 PG가 뒤처져
  있어도 무해하다. 다만 **색인 시점에는** PG를 읽으므로, 동시에 도는 재색인이 옛 태그를 다시 써넣을
  수 있다 — §5-1의 `acc_tag_ver` 정합성 검사가 이 경우의 최종 안전망이다.
- **넓히는 방향(권한 부여)**: 같은 순서라도 피해가 없다 — 부여가 살짝 늦어질 뿐, 과다노출은 없다.
- **재임베딩은 불요**(순수 메타 갱신, llmhub의 `E6`와 같은 원리) — `update_by_query`로 `access_tags`
  필드만 갱신하면 된다.

부분 실패(일부 조각만 갱신되고 중단) 시 재시도·감지 절차는 §5-4 Q3에서 확정했다.

### 2-3. `doc_key` 충돌 방지 — `(department, doc_key)` 복합 유니크

전역 유니크 대신 **`(department, doc_key)` 복합 유니크**로 네임스페이스 자체를 분리한다(대안이던
"글로벌 유니크 + 소유권 검사로 거부(409)"보다 이 방식을 선택함).

- **department 도출**: **(4라운드 정정)** 원래 "요청 시점 JWT claim에서 순수 도출"이라고 적었으나,
  이러면 COMMON처럼 사용자의 기본 소속이 아닌 값은 애초에 도출될 수 없어 §2-3 뒤쪽의 COMMON 설명과
  모순됐다. 실제로는 **업로드 요청이 `department`를 명시 파라미터로 받고, 서버가 요청자의 claim
  집합에 그 값이 포함되는지 검증**한다(다중 부서 소속 admin이 실제로 있어 claim이 다값일 수 있음,
  §5-4 Q2 확정). 검증 자체는 llmhub의 `S4` 원칙과 같은 자리(앞단 게이트)에서 이뤄진다. `app_user`
  테이블엔 저장하지 않는다(`S3` 원칙 — 역할/부서를 저장하면 Keycloak과 이중 관리가 된다) — 이건
  원래 맞았고 그대로 유지.
- **재업로드 시 department는 고정**된다(재파생하지 않음) — §5-3 참고.

**전사 공통 문서**: `(department, doc_key)`만 정하면 부서 공통이 아닌 전사 규정 같은 문서를 특정
부서 소유로 억지로 배정하거나 부서마다 중복 업로드해야 하는 문제가 생긴다. 해결책:

- **`department = 'COMMON'`** 센티널 값을 쓴다. `NULL`은 쓰지 않는다 — 대부분 DB에서 `NULL`끼리는
  유니크 제약상 서로 다른 값으로 취급되어(PostgreSQL 15+의 `NULLS NOT DISTINCT`를 쓰지 않는 한) 같은
  공통 문서를 여러 번 올려도 충돌 감지가 안 된다. 문자열 센티널이 명시적이고 이식성도 좋다.
  `(department, doc_key)` 유니크 제약은 그대로 두면 `('COMMON', 'hr-policy-2026')`도 자기
  네임스페이스를 가져 부서별 키와 충돌하지 않고, COMMON끼리는 충돌 감지가 된다.
- **별도 테이블/스코프 분리는 권장하지 않는다.** 공통 문서도 상태 추적(2-1)·재색인·불변식 로직이
  부서 문서와 동일하므로 네임스페이스만 다르면 충분하다. 테이블을 쪼개면 그 로직을 두 벌 유지해야
  한다.
- **COMMON 쓰기 권한**은 스키마가 아니라 인가(조직 정책) 문제다 — `S4`와 같은 자리(앞단 게이트)에서
  "이 admin의 department claim이 COMMON 쓰기 권한을 포함하는가"를 판정하면 된다. 스키마엔 영향이
  없으므로 지금 결정하지 않아도 마이그레이션이 아파지지 않는다(§4로 이동).

**경고 — id 유도 함정(중요):** 유니크 제약만 `(department, doc_key)`로 바꾸고 문서 id 유도·재업로드
조회·upsert 로직을 여전히 `doc_key` 단독 기준으로 두면 안 된다. llmhub 참고:

```java
// IndexingService.run()
String documentId = DocumentId.of(docKey).toString();   // doc_key만으로 결정적 해시

// PostgresDocumentRepository.upsert()
jpaRepository.findByDocKey(docKey)                       // doc_key만으로 조회
```

llmhub은 `document.id`를 `doc_key`만으로 결정적으로 유도하고(`DocumentId.of(docKey)`), 재업로드
판정도 `findByDocKey(docKey)` 하나만 본다. 만약 유니크 제약만 `(department, doc_key)`로 바꾸고 **id
유도 함수와 조회 로직은 `doc_key`만 계속 쓰면**, 서로 다른 부서가 같은 `doc_key`를 올렸을 때 PG
유니크 제약은 통과하는데 파생되는 문서 id(또는 ES `documentId`)가 같은 값이 되어버려 PK 충돌로
인서트가 실패하거나, 구현 방식에 따라 서로 다른 부서 문서가 같은 ES 문서를 가리키는 조용한 오염으로
이어질 수 있다.

→ **id 유도 · 조회 · upsert 세 곳 모두 `(department, doc_key)` 기준으로 같이 바뀌어야 한다.**

---

## 3. 즉시 반영 체크리스트

마이그레이션이 아프기 전에(데이터 쌓이기 전에) 넣는 게 이득인 것들 — 설계 방향 확정을 안 기다려도 됨:

- [ ] `chat_message.seq` (세션 내 단조 증가) — 1절 4번. 데이터 쌓인 뒤 넣으면 기존 행에 소급 채워야
      해서 훨씬 귀찮아진다. 발급 방식은 §5-6(세션 `next_seq` 카운터, 원자적 UPDATE).
- [ ] `chat_session.next_seq` (정수 카운터) — §5-6.
- [ ] `chat_session(user_id, updated_at DESC)` 인덱스 — 1절 작은 것들.
- [ ] `document.uploaded_by`의 `ON DELETE SET NULL`을 스키마 문서(`03-data-model.md`)에 명시 — 구현
      결정 자체는 이미 됨(2-3 위 경고와 별개).
- [ ] `app_user` 생성 시점(JIT + `on conflict do nothing`)을 `03-data-model.md`에 한 줄 추가.
- [ ] `document.department`(문자열, `COMMON` 센티널 허용) + `(department, doc_key)` 복합 유니크 —
      §2-3/§5-3. id 유도·조회·upsert 세 곳 모두 이 키 기준으로 함께 변경할 것(§2-3 경고).
- [ ] `document.status`, `status_at`, `pending_idx_run_id`, `cur_idx_run_id`, `last_error`,
      `attempt_cnt` — §2-1(4라운드 정정 버전) + §5-4 Q1.
- [ ] `document.acc_tag_ver`(정수) + 조각에 복사 — §5-1. 정합성 검사(`acc_tag_ver` 불일치 조각 재갱신)
      배치도 같이 필요.

---

## 4. 다음 라운드에 확인하고 싶은 것

- **`index_run`을 별도 테이블로 뺄지, `document`에 컬럼만 둘지** — 관측성(observability) 요구 수준에
  따라. 로그/모니터링이 이미 있으면 컬럼만으로 충분할 수 있다.
- **department claim이 Keycloak 토큰 어디서 나오는지**(realm role? group? custom claim?) — access_tags
  확정 로직과 같은 자리에서 다뤄야 하니, 이 매핑 규칙이 정해지면 `doc_key` 스코프 로직도 같이
  확정된다.
- **`update_by_query` 부분 실패 시 재시도·감지 방법** — llmhub도 이 케이스는 안 다뤄봤다. 옹기종기가
  먼저 설계하게 될 것이다.
- **COMMON(전사 공통) 문서 쓰기 권한을 누가 갖는지** — 조직 정책. 스키마엔 영향 없음.

---

## 5. 3라운드 — 교차 문제와 나머지 질문 응답

> 이 절도 2절과 같은 성격이다: llmhub 코드로 검증된 적 없는 새 설계.

### 5-1. 결정 1×2 충돌 — 인정, `acc_tag_ver`로 보완

옹기종기 에이전트가 정확히 짚었다: "ES 먼저, PG 나중" 규칙은 **짧은 갱신 창**은 닫지만, 태그 갱신과
**동시에 도는 재색인**이 옛 태그로 새 조각을 색인해버리면 조용히 영구적으로 재노출된다. 순서 규칙만으로는
막을 수 없다 — 채택한다.

**채택하는 보완책:**
- `document.acc_tag_ver`(정수, 태그가 바뀔 때마다 증가) 추가. 조각에도 복사해서 색인 워커가 자기가
  읽은 버전을 박는다.
- **정합성 검사**: `doc_id=X AND acc_tag_ver != doc.acc_tag_ver`인 조각이 있으면 그 조각만 재갱신.
- **최소 방어**로 `status=PROCESSING`인 문서는 태그 변경을 거부하는 것도 같이 둔다(태그 변경 중 색인
  시작을 막는 반대 방향은 doc 단위 잠금 없인 완전히 못 막으므로, `acc_tag_ver` 정합성 검사가 최종
  안전망이다 — 순서·잠금은 "웬만하면 안 어긋나게", 버전 검사는 "어긋나도 알아챈다").

**(4라운드 추가) 불변식 2의 일시 위반을 명시한다.** "ES-먼저, PG-나중" 규칙이 실행되는 동안(그리고
동시 재색인이 겹치는 동안) 조각의 `access_tags`가 상위 `document.access_tags`와 **일시적으로
어긋난다** — 원본 번들 `03-data-model.md`의 불변식 2("조각의 access_tags는 상위 document의
access_tags와 일치한다")는 항상 참이 아니라 **"`acc_tag_ver` 일치 시에만" 참인 불변식으로 다시
읽어야 한다.** 복구 수단은 위 정합성 검사(`acc_tag_ver` 불일치 조각 재갱신)다. onggijonggi가 원본
번들의 불변식 목록에도 이 캐벗을 반영할 것 — 이 파일(`review-response.md`)은 1라운드 산출물
(`03-data-model.md`/`README.md`)을 수정하지 않는다는 원칙이라 여기 문서화만 해 둔다.

### 5-2. 비동기 색인 보완 3가지 — 전부 채택

- **멈춘 PROCESSING 감지**: `document.status_at`(상태 진입 시각) 추가. "N분 초과 PROCESSING = 죽은
  것"으로 판정하는 타임아웃/리스 방식 채택. 재시도 트리거의 전제 조건이므로 필수로 둔다.
- **PROCESSING 중 구버전 노출 — "허용"으로 확정.** 이건 새 제안이 아니라 **llmhub이 이미 검증해둔
  선택의 재사용**이다. `IndexingService`의 순서(새 조각 색인 완료 → 구버전 삭제 → PG 커밋, `S17`×`S8-3`)가
  정확히 "교체 중에도 뭔가는 계속 검색되게" 하려고 그렇게 짜여 있다. 같은 논리를 그대로 가져간다 —
  비정규화나 검색 시 PG 재조회는 불필요.
- **부분 색인 조각의 수명**: 지적대로 `idx_run_id != cur_run` 삭제 조건이 자가 치유를 제공한다.
  타임아웃(위 항목)을 짧게 잡을수록 중복 근거 노출 창이 줄어든다는 트레이드오프를 문서화해 둔다.

### 5-3. `department`를 문서 정체성의 일부로 고정 — 재파생 금지

`doc` 테이블에 `department` 컬럼이 필요하다는 지적을 받아들인다(유니크 제약의 일부이니 당연히
저장돼야 한다 — `app_user`에 저장하지 않는 것(S3)과 문서 메타데이터로 저장하는 것은 다른 층위라
원칙과 충돌하지 않는다).

**"부서 바뀐 admin이 재업로드 못 함"을 버그가 아니라 의도로 확정한다.** `department`는 `doc_key`와
마찬가지로 **문서 정체성의 일부**이므로 재업로드/재색인 때 요청자의 현재 claim에서 다시 파생하지
않는다. 매번 다시 파생하면 복합 유니크 키의 절반이 조용히 바뀌어 기존 행을 못 찾고 **새 문서가
중복 생성**되는 쪽이 진짜 위험이다(llmhub이 `doc_key`를 재업로드에도 안 바꾸는 것과 같은 이유,
`S17`). 부서 이관이 필요하면 별도의 명시적 "이관" 작업으로 다루고, **지금은 만들지 않는다**(v0 패턴 —
자리는 열어두고 구현은 미룬다).

**id 유도 마이그레이션 비용 — 타이밍 확인**: 이미 색인된 문서가 있는 상태에서 `DocumentId` 유도
방식을 바꾸면 기존 `doc_id`가 전부 달라져 전량 재색인이 불가피하다는 지적도 맞다. 옹기종기가 아직
프로덕션 데이터가 없다면 지금이 유일하게 싼 시점이라는 데 동의한다 — 실제로 데이터가 있는지는
onggijonggi 팀만 아는 사실이니 팀이 확인할 것.

### 5-4. 4문항 응답에 대한 채택 여부

- **Q1 (index_run 테이블 vs 컬럼)**: **컬럼 우선 채택.** `status`, `status_at`, `pending_idx_run_id`,
  `cur_idx_run_id`, `last_error`(§2-1 4라운드 정정 버전) + `attempt_cnt`(런 시작마다 +1, 단순 카운터라
  컬럼으로 충분)로 시작하고, **개별 시도별** 사유·소요시간까지 봐야 하면 그때 `index_run`을 추가한다.
  나중에 테이블을 붙여도 기존 컬럼을 안 건드려도 되니 되돌리기 쉬운 방향이라는 근거에 동의한다.
- **Q2 (department claim 출처) — 확정.** **한 admin이 여러 부서에 소속되는 실제 사례가 있다**(팀
  확인). 그래서 department는 JWT에서 순수 파생하지 않는다 — **업로드 요청이 `department`를 명시
  파라미터로 받고, 서버가 요청자의 claim 집합에 그 값이 포함되는지 검증**한다. `COMMON`은 그 값을 쓸
  수 있도록 허가된 사람만 보내는 특수 claim으로 자연스럽게 편입된다(4번 COMMON 권한 질문이 이걸로
  흡수됨, 지적대로). realm role보다는 group 또는 custom claim 중 조직 부서 구조가 계층적이냐 평면이냐로
  고르면 된다는 가이드에도 동의 — 다만 다중 소속이 확인됐으니 어느 쪽이든 **claim이 다값**이라는 전제로
  설계한다.
- **Q3 (update_by_query 부분 실패) — 채택.** 제안된 절차를 그대로 가져간다: `wait_for_completion=false`
  + 태스크 폴링 → `failures[]`/`version_conflicts` 확인 → 좁히기는 `conflicts=abort`(충돌 조각이 옛
  태그를 유지하는 `proceed`는 좁히기에 위험) → 완료 후 `acc_tag_ver` 불일치 카운트로 검증 → **0 확인
  후에만 PG 갱신**. `acc_tag_ver`(5-1)가 있어서 이 검증이 단순 쿼리로 끝난다는 이점도 그대로 살아난다.
- **Q4 (COMMON 쓰기 권한)** — Q2로 흡수. 스키마엔 영향 없지만 **API 계약(업로드 엔드포인트가
  `department` 파라미터를 받고 검증한다)에는 영향이 있다**는 지적을 받아들여 §3 체크리스트가 아니라
  §2-3 설계 자체에 반영했다.

### 5-5. `chat_msg.seq` — 채택 유지, 누락 아님

3절(즉시 반영 체크리스트)에 이미 있다. 2라운드 "결정된 것" 요약에 3가지 새 설계만 강조하느라
재언급을 안 한 것뿐, 채택이 철회된 적 없다.

### 5-6. `chat_message.seq` 발급 방식 — (4라운드 추가) 새로 확정

`seq`를 넣기로 한 것과 별개로 **어떻게 발급할지가 안 적혀 있었다.** `SELECT MAX(seq) WHERE
session_id=X` 후 그 값+1로 INSERT하는 방식은 동시 append에서 경합한다(TOCTOU) — 특히
PostgreSQL 기본 격리수준(READ COMMITTED)에서는 "같은 트랜잭션 안에서 처리한다"는 것만으로는
안 막힌다. 두 트랜잭션이 동시에 같은 MAX를 읽고 같은 다음 값을 계산할 수 있다.

**채택하는 방식:** `chat_session`에 `next_seq`(정수, 기본 0) 카운터를 추가한다. `appendTurn()`이
이미 세션 행을 같은 트랜잭션에서 읽고 갱신(`touch`)하므로, 그 지점에서 원자적으로 카운터를
올린다 — `SELECT ... FOR UPDATE`로 세션 행을 잠그고 읽은 뒤 +1씩 배정(user=n, assistant=n+1)하거나,
`UPDATE chat_session SET next_seq = next_seq + 2 WHERE id = ... RETURNING next_seq`처럼 단일 원자적
UPDATE로 두 값을 한 번에 확보하는 방식이 더 간단하다. 대안으로 `(session_id, seq)` 유니크 제약 +
충돌 시 재시도도 가능하지만, 재시도 루프가 추가되므로 카운터 방식을 우선 추천한다.

---

## 경고

- 2절·5절의 설계 제안은 **llmhub 코드로 검증된 사실이 아니다.** llmhub엔 비동기 워커·부서 스코프·
  acc_tag 갱신 API·`acc_tag_ver`가 없으므로 실전에서 검증되지 않았다. 1절(코드 인용 있는 항목)과
  구분해서 읽을 것.
- 이 문서는 이 시점(3라운드)까지의 논의 스냅샷이다. 후속 라운드 결과는 이 파일에 계속 이어 붙일 것 —
  이전 라운드 내용을 지우지 말 것(대화 이력으로서의 가치).
