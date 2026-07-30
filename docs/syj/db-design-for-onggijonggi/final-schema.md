# 최종 스키마 한 장 — 14라운드 결과 조립본

`review-response.md`(라운드 1~14)의 결정이 **§2-1·§2-3a·§0-2·§5-1·§5-7·§3에 흩어져 있어** 조립이
필요하다. 이 파일이 그 조립본이다.

> ## 읽는 법 — **이름은 llmhub 원본이고, 계약은 "역할"이다**
> 옹기종기는 자기 용어집으로 **이름만 바꿔** 쓴다(`README.md` §이름 규칙). 이름이 달라도 **역할·제약·
> 등급이 같으면 같은 설계**다.
>
> | 등급 | 뜻 |
> |---|---|
> | **A** | **착수 전 스키마에 있어야 한다.** 데이터가 쌓이면 소급이 불가능하거나 전량 재색인이다 |
> | **B** | **착수 전 로직 규칙.** 코드 골격에 박히면 걷어내는 비용이 코드 수정에 그치지 않는다 |
> | **C** | 나중에 해도 된다. 설정·문서·추가 테이블·인덱스라 이미 쌓인 데이터가 걸림돌이 아니다 |
>
> 등급의 판정 기준은 하나다 — **"나중에 추가할 때 이미 쌓인 데이터가 문제가 되나?"**
> (`review-response.md` §0-2). **왜 이렇게 정했나는 전부 그 문서에 있고**, 아래 각 항목의 `§` 표시가
> 그 자리를 가리킨다.
>
> **모든 시각 컬럼은 `timestamptz`(UTC)다** — 스위퍼가 PG 시각과 ES `indexed_at`을 직접 비교하므로
> 필수다(§3 A, 14라운드).

---

## PostgreSQL

### `app_user` — 변경 없음

| 필드 | 역할 | 등급 |
|---|---|---|
| `id` | PK (UUID) | — |
| `keycloak_subject` | Keycloak `sub`. unique | — |
| `created_at` | | — |

- **역할·부서를 저장하지 않는다**(S3) — Keycloak이 부여하고 요청 시점에 태그·부서로 변환한다.
- **행 생성은 JIT** — 첫 요청 시 `insert … on conflict (keycloak_subject) do nothing` + 재조회.
  경합을 그냥 `insert`로 두면 동시 첫 요청에서 실패한다(`../keycloak-appuser-provisioning-from-llmhub.md`).

### `document` — 논리 문서 (14라운드에서 가장 많이 바뀐 테이블)

| 필드 | 역할 | 등급 | 근거 |
|---|---|---|---|
| `id` | PK. **UUID v4 서로게이트** — 업무 키에서 유도하지 **않는다** | **A** | §2-3a |
| `department` | 관리 주체 + 유니크 스코프. `COMMON` **센티널 문자열** 허용(`NULL` 금지) | **A** | §2-3 |
| `doc_key` | 업무 키. **admin이 정하는 임의 문자열** | **A** | §0 |
| — | **부분 유니크 인덱스**: `UNIQUE (department, doc_key) WHERE deleted_at IS NULL` | **A** | §5-7 |
| `access_tags` | `text[]`. **접근 태그의 유일 원천**(S18). 빈 배열 금지 check | — | V1 |
| `acc_tag_ver` | 정수. 태그가 바뀔 때마다 증가. **조각에도 복사** | **A** | §5-1 |
| `status` | `PENDING`(큐 적재) / `PROCESSING`(워커 처리 중) / `READY` / `FAILED` | **A** | §2-1 |
| `status_at` | 상태 진입 시각. **멈춘 런 감지용**(PENDING도 대상) | **A** | §5-2 |
| `pending_idx_run_id` | **진행 중인** 색인 실행. 런 시작 시 즉시 기록 | **A** | §2-1 |
| `cur_idx_run_id` | **마지막으로 성공 확정된** 실행. **성공 시에만** 갱신 | **A** | §2-1 |
| `last_error` | 마지막 실패 사유 | **A** | §2-1 |
| `attempt_cnt` | 런 시작마다 +1. 성공·새 파일 재업로드 시 0 | **A** | §5-2·§5-4 |
| `embedding_model` | 모델 이름 | — | V1 |
| **`embedding_fingerprint`** | 모델 **지문**. 이름만으론 같은 태그로 가중치가 바뀌는 걸 못 잡는다 | **A** | §0 CLOSED |
| `chunking_version` | **청킹 결과에 영향 주는 파라미터 전부 반영**(크기·오버랩·분할 규칙) | — | §0 CLOSED |
| `deleted_at` | 소프트 삭제 시각. nullable | **A** | §5-7 |
| `deleted_by` | **`keycloak_subject` 값 복사**(FK 아님) — 감사 정보라 계정 삭제에 파괴되면 안 된다 | **A** | §5-7 |
| `current_version_id` | 지금 검색되는 버전 → `document_version.id`. **nullable**(순환 참조) | **A** | §0-2 |
| `next_version_no` | `version_no` 발급 카운터 | **A** | §0-2 |
| `created_at` · `updated_at` | | — | V1 |

**빠진 것**: `uploaded_by`는 이 테이블에서 **제거**되어 `document_version`으로 이동했다(§0-2).

**제약 — `document` 행은 하드 삭제하지 않는다**(§5-8). 스위퍼 커버리지의 전제다(모든 ES 조각에 PG 부모가
있다는 것). 삭제 행이 쌓여 테이블이 단조 증가하며, 정리하려면 **행 purge와 ES 조각 삭제를 세트로** 해야
한다.

**`current_version_id`의 NULL이 상태를 표현한다**(§0-2):

| 상태 | 뜻 |
|---|---|
| `current_version_id IS NULL` + 버전 행 있음 | 업로드됐지만 **아직 한 번도 색인 성공 못 함** |
| `current_version_id` 있음 + `status='PROCESSING'` | **구버전이 검색되는 중**, 새 버전 색인 중 |

→ **"검색 가능한 문서" = `current_version_id IS NOT NULL`.**
삽입 순서는 `document` → `document_version` → 포인터 UPDATE.

### `document_version` — 업로드 이력 (신규)

| 필드 | 역할 | 등급 |
|---|---|---|
| `id` | PK (UUID) | **A** |
| `document_id` | → `document.id` | **A** |
| `version_no` | 문서 내 단조 증가. `document.next_version_no`로 **원자적 발급**(`MAX+1` 금지) | **A** |
| `filename` | 그 버전의 파일명 | **A** |
| `original_path` | 그 버전의 보관 원본. **재업로드해도 구 원본을 지우지 않는다** | **A** |
| `uploaded_by` | **`keycloak_subject` 값 복사**(FK 아님) — `deleted_by`와 같은 이유 | **A** |
| `uploaded_at` | **이력 조회의 정렬 키** | **A** |

**왜 이 분할인가**: 색인 상태는 "지금 검색되는 것"의 속성이라 `document`에 남고, 이 테이블은 순수하게
**"업로드된 파일의 이력"**만 담는다. `embedding_model`·`chunking_version`도 `document`에 남는다 — 그건
버전의 속성이 아니라 **현재 색인의 속성**이다(같은 버전을 모델만 바꿔 재색인할 수 있다).

**이력 조회 규칙**(§0-2): `document_id`가 아니라 **`(department, doc_key)`로** 삭제된 행까지 모으고,
그 버전 행들을 **`uploaded_at`으로 정렬**한다. 긴급 정정(삭제→업로드)이 `document` 행을 새로 만들어도
체인이 이어지고, 복구 API가 끼어도 순서가 맞는다.

### `chat_session`

| 필드 | 역할 | 등급 |
|---|---|---|
| `id` · `user_id`(→`app_user`, `ON DELETE CASCADE`) | | — |
| `title` | 서버가 첫 질문에서 생성. **`NOT NULL` 유지** — 이름 변경은 nullable과 무관하다 | — |
| **`next_seq`** | `chat_message.seq` 발급 카운터 | **A** |
| `created_at` · `updated_at` | `updated_at`은 메시지 추가 시 갱신(앱 레벨) | — |

인덱스 `(user_id, updated_at DESC)` — 실제 정렬 기준이 `updated_at`이다. **C**(언제든 추가).

### `chat_message`

| 필드 | 역할 | 등급 |
|---|---|---|
| `id` · `session_id`(→`chat_session`, `ON DELETE CASCADE`) | | — |
| `role` | `USER` / `ASSISTANT` / (`SYSTEM`) | — |
| `content` · `sources_json` | 근거 스냅샷은 그 턴에 사용자가 본 것(UI 재현용) | — |
| **`seq`** | **세션 내 단조 증가. 이력 정렬은 이것으로 한다** | **A** |
| `created_at` | | — |

**`created_at` 정렬은 안 된다** — 한 턴의 user/assistant를 같은 트랜잭션에서 같은 시각으로 저장하면
동률이 되고, SQL은 동률 순서를 보장하지 않는다(§1 4번). 발급은 `chat_session.next_seq`를
`+ :n`(그 턴에 저장할 실제 개수)만큼 **원자적 UPDATE + `RETURNING`**하고, `n`개면 `returned-n+1`~`returned`를
배정한다(§5-6 — `RETURNING`은 **증가 후** 값이라 off-by-one 주의).

### `audit_log` — 변경 없음

FK를 두지 않고 `requester_id`를 **`keycloak_subject` 값 복사**로 둔다(S5) — 사용자·세션 삭제와 무관하게
남아야 한다. `trace_id` · `question` · `answer` · `sources_json` · `outcome`(`COMPLETE`/`CANCELLED`/`ERROR`)
· `created_at`.

- **감사 범위 설정값은 제거하고 전문 기록으로 고정**한다(§0 CLOSED) — 감사 요구가 "내부 운영 확인용"
  단일 정책이라 배포처별 분기가 필요 없다.
- `sources_json`이 `chat_message`와 **이중 저장되는 것은 선택이 아니라 대가**다 — 한쪽은 세션 삭제와
  함께 사라지고 한쪽은 남아야 한다(§0 CLOSED).

---

## Elasticsearch 조각 인덱스

기존 10필드(`es-chunk-index.json`) + 신규 2필드:

| 필드 | 역할 | 등급 |
|---|---|---|
| `chunk_text` | `analyzer: nori` — **인덱스 생성 시 고정, 나중에 못 바꿈** | — |
| `embedding` | `dense_vector`, `dims`는 **모델 출력 차원. 생성 시 고정** | — |
| `document_id` · `document_name` · `location` | 상위 문서 참조·표시·위치 | — |
| `access_tags` | **`document`에서 복사한 사본.** kNN **pre-filter**에 필수 | — |
| **`acc_tag_ver`** | 색인 워커가 자기가 읽은 태그 버전을 박는다. **정합성 검사의 근거** | **A** |
| `indexed_at` | 조각이 쓰인 시각. **스위퍼 컷오프에 쓰인다** | — |
| `embedding_model` · `embedding_dim` | | — |
| **`embedding_fingerprint`** | 그 조각이 어떤 가중치로 만들어졌나 | **A** |
| `indexing_run_id` | 어느 런의 조각인가. **스위퍼·구버전 삭제의 근거** | — |

**`access_tags` 사본을 두는 이유(§0 CLOSED)**: ES는 `knn` 절의 `filter`만 **후보군 진입 전 pre-filter**로
적용한다. 조각에 태그가 없으면 **pre-filter 자체가 불가능**해 후보를 뽑은 뒤 거르는 post-filter만 남고,
그러면 **권한 분포에 따라 결과가 얼마나 비는지가 예측 불가능**해진다. `acc_tag_ver`·2단 정합성 검사·
ES-먼저 순서·스위퍼의 복잡도는 **이 선택의 대가로 의식적으로 받아들인 것**이다.

**별칭(alias)으로 접근할 것** — `dims`·분석기가 생성 시 고정이라 **재색인이 언젠가 반드시 필요해지는데**,
처음부터 별칭을 쓰면 무중단 전환이 공짜다. 나중에 붙여도 데이터는 안 상하므로 **C**지만, C 중에서는
먼저 할 것이다(§0-2 전수 재검).

---

## B급 — 착수 전에 잡아야 할 로직 규칙 5개

스키마만 옮기면 **필드는 있는데 아무도 안 쓰는 상태**가 된다. 다섯 개는 코드 골격에 박히므로 함께 잡는다.

| 규칙 | 요지 | 근거 |
|---|---|---|
| **upsert 조회에 `deleted_at IS NULL`** | 빠뜨리면 소프트 삭제 행을 찾아 되살려 **삭제 감사 기록을 덮는다 — 에러가 안 난다.** 부분 유니크 인덱스와 술어가 같아 그 인덱스를 그대로 탄다. `findFirstBy…`류는 조용히 하나를 고르므로 특히 위험 | §2-3a |
| **워커 최종 커밋은 조건부 UPDATE** | `WHERE id=? AND status='PROCESSING' AND pending_idx_run_id=? AND deleted_at IS NULL`. `affected rows = 0`이면 무효화 판정. 읽고-확인-쓰기는 TOCTOU다. `FAILED` 경로는 **`RETURNING pending_idx_run_id`**로 정리 대상을 먼저 확보 | §5-8·§5-2 |
| **고아 조각 스위퍼** | `idx_run_id`가 `cur`도 `pending`도 아닌 조각을 정리. 컷오프는 **배치 시작 시각**(`indexed_at < T_snap - 스큐 여유`) — `T_snap`은 **문서를 읽기 전에** 찍는다. **스위퍼만** `deleted_at IS NULL` 예외(삭제된 문서도 순회해야 조각이 회수된다). NULL 비교 주의(`IS DISTINCT FROM`) | §5-1a |
| **태그 갱신은 ES 먼저, PG 나중** | 좁히기는 노출이 즉시 닫힌다. 다만 색인 워커는 PG를 읽으므로 동시 재색인이 옛 태그를 다시 쓸 수 있다 → **워커 완료 직후 자체 검증 + 주기 배치** 2단이 최종 안전망 | §2-2·§5-1a |
| **`attempt_cnt` 상한** | 재시도 대상 = `status IN ('PENDING','FAILED') AND attempt_cnt < 상한 AND deleted_at IS NULL`. 영구 실패 조회 = `status='FAILED' AND attempt_cnt >= 상한`(새 상태값을 만들지 않는다) | §5-2 |

**불변식 2는 항상 참이 아니다** — "조각의 `access_tags`가 상위 문서와 일치한다"는 **`acc_tag_ver`가
일치할 때만** 참이다. ES-먼저 갱신 중과 동시 재색인 중에는 일시적으로 어긋나며, 복구 수단이 위
정합성 검사다(§5-1). **검증 스크립트를 쓸 때 이 캐벗을 반영할 것.**

---

## C급 — 나중에 (착수를 막지 않는다)

파라미터 숫자(리퍼 타임아웃 · 배치 주기 · `attempt_cnt` 상한 · 원본 유예 기간) · 운영 런북(긴급 정정 ·
부서 개편) · 복구 API 세부 · **지문 불일치 감지 배치**(정합성 배치에 얹는다 — 미루면 지문 값만 쌓이고
아무도 안 봐서 A가 무의미해진다) · `index_run` 테이블 · 인덱스 2개 · 원본 유예 삭제 배치 · ES 별칭.

전체 목록과 각 항목의 "왜 C인가"는 `review-response.md` §3 C에 있다.

---

## 아직 안 정한 것 둘 (설계가 아니라 조직 사실)

| 항목 | 왜 열려 있나 |
|---|---|
| `department` claim이 Keycloak 토큰 어디서 나오나 | group vs custom claim. **다중 소속이 확인됐으므로 어느 쪽이든 다값 전제.** API 계약이 "명시 파라미터 + claim 집합 검증"이라 출처가 무엇이든 흡수된다 — 다만 **구현 순서상 가장 먼저 답이 필요하다**(업로드 API 모양을 강제한다) |
| 파라미터 숫자들 | 설정이라 데이터에 흔적이 안 남는다. 리퍼 타임아웃은 **비단조**(짧을수록 좋은 게 아니다 — 살아있는 느린 워커를 오판해 고아 조각이 늘어난다)라 실측이 필요하다 |
