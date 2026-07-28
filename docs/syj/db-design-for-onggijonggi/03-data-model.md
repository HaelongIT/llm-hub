# 03 · 데이터 모델

> 실제 컬럼 타입·인덱스·제약은 구현 시 확정. 아래는 필수 필드와 관계·불변식.

## PostgreSQL

### `app_user`
로컬 사용자 식별. 인증은 Keycloak, 여기엔 참조용 최소 정보만.

| 컬럼 | 설명 |
|---|---|
| `id` PK | 내부 식별자 |
| `keycloak_subject` UNIQUE | Keycloak sub |
| `created_at` | |

- **역할 미저장.** 역할은 Keycloak이 부여하고 요청 시점에 태그로 변환(S3). DB 저장 시 이중 관리가 되므로 넣지 않는다.

### `document`
원본 문서 레코드. 조각(ES)의 상위. **접근 태그의 원천(S18).**

| 컬럼 | 설명 |
|---|---|
| `id` PK (= document_id) | 시스템 생성 |
| `doc_key` UNIQUE | 교체 판단용 문서 식별 키(업로드 시 제공, S17) |
| `filename` | |
| `original_path` | 보관된 원본 참조(S16) |
| `access_tags[]` | **접근 태그의 유일한 원천(S18)** |
| `embedding_model` | 색인에 쓴 임베딩 모델(재색인 대상 식별) |
| `chunking_version` | 색인에 쓴 청킹 버전 |
| `uploaded_by` FK→app_user | |
| `created_at`, `updated_at` | 교체 시각 추적 |

- 재색인은 이 레코드의 `original_path` 원본을 다시 읽어 수행.
- 같은 `doc_key` 재업로드 = 신버전 색인 후 구버전 조각 삭제(S17).

### `chat_session`
세션(S2). 사용자 소유.

| 컬럼 | 설명 |
|---|---|
| `id` PK | |
| `user_id` FK→app_user | |
| `title` | |
| `created_at`, `updated_at` | 필수(향후 만료 배치 전제) |

- 사용자 삭제 = 행 삭제 → 메시지 cascade.

### `chat_message`
메시지(S2·S6).

| 컬럼 | 설명 |
|---|---|
| `id` PK | |
| `session_id` FK→chat_session (ON DELETE CASCADE) | |
| `role` | user / assistant |
| `content` | |
| `sources_json` (nullable) | assistant 근거 스냅샷(JSON) |
| `created_at` | |

- 근거는 JSON 스냅샷: "그 응답 시점에 무엇을 봤나"의 박제.

### `audit_log`
감사 로그(S5). 이력과 **완전 별도, 세션 FK 없음.**

| 컬럼 | 설명 |
|---|---|
| `id` PK | |
| `trace_id` | 요청 단위 추적 ID |
| `requester_id` | 사용자 식별자 **값 복사**(FK 아님) |
| `question`, `answer` | |
| `sources_json` | 근거 스냅샷 |
| `created_at` | |

- **FK 미사용이 핵심.** 사용자·세션 삭제와 무관하게 유지(S5). 기록 범위는 설정(E5).

## Elasticsearch

### 조각 인덱스
문서 1개 = 조각 여러 개. 조각 1개 = ES 문서 1개. 상위 `document`(PG)와 `document_id`로 연결.

| 필드 | 설명 |
|---|---|
| `chunk_text` | 원문. **BM25 분석기 = nori**(S15). 하이브리드 검색용 |
| `embedding` | dense_vector, similarity=cosine |
| `document_id` | 상위 document 연결 |
| `document_name` | |
| `location` | 페이지/섹션 또는 순번 |
| `access_tags[]` | **document에서 복사된 사본**(원천은 document, S18) |
| `indexed_at` | |
| `embedding_model`, `embedding_dim` | 재색인 대상 식별(E9) |
| `indexing_run_id` | 색인 실행 단위 식별자. 교체 시 구버전 조각 삭제에 쓴다(S17) |

- 검색 필터: 사용자 태그 집합 ∩ `access_tags` ≠ ∅ (S3·S4).
- 신규 메타 필드 추가는 재색인 불요(E6).
- 임베딩 차원 변경은 **새 인덱스 생성** 필요(dense_vector 차원은 인덱스 생성 시 고정).

### 교체 시 구버전 조각 식별 (S17)

같은 `doc_key` 재업로드는 `document` 행을 갱신하므로 신·구 조각이 `document_id`를 공유한다. `document_id`만으로는 둘을 구별할 수 없고 `indexed_at` 기준 삭제는 경합에 취약하다. 그래서 조각마다 그 조각을 만든 색인 실행의 `indexing_run_id`를 기록한다.

교체 절차(순서 절대 준수):
1. 새 `indexing_run_id`를 발급하고, **임베딩을 전량 완료한 뒤** 신버전 조각을 bulk 색인한다.
2. 신버전 색인 성공을 확인한다.
3. `document_id = X AND indexing_run_id != <이번 실행>` 조건으로 구버전 조각을 삭제한다.

1단계 도중 실패하면 이번 실행의 조각만 정리하고 구버전을 그대로 둔다. 임베딩을 먼저 전량 끝내는 이유는, 부분 색인된 신버전 조각이 구버전과 함께 검색되어 중복 근거가 나오는 것을 막기 위해서다(S17 × S8-3).

## 불변식 (테스트로 강제할 것)

1. 모든 조각은 필수 메타데이터 7종을 빠짐없이 가진다.
2. 조각의 `access_tags`는 상위 document의 `access_tags`와 일치한다(사본).
3. `audit_log`에는 어떤 FK도 없다.
4. 같은 `doc_key`로 두 번 색인 후, 구버전 조각은 존재하지 않는다.
5. 색인에 쓴 `embedding_model`과 검색에 쓰는 임베딩 모델은 동일하다.
