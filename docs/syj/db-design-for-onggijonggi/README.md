# llmhub DB 설계 이관 번들 → 옹기종기

llmhub의 **DB 스키마 설계**를 옹기종기(onggijonggi) 프로젝트로 그대로 들고 가기 위한 자체 포함 번들이다.
llmhub의 특정 시점 스냅샷이며, 원본은 llmhub 레포다.

## 이 번들에 든 것

| 파일 | 무엇 |
|---|---|
| `migration/V1~V4.sql` | PostgreSQL 스키마 (Flyway 마이그레이션 4개, 테이블 5개) — **원본 그대로** |
| `03-data-model.md` | 설계 본체: PG 5테이블 + ES 조각 인덱스 필드 + 불변식 5가지 — **원본 그대로** |
| `es-chunk-index.json` | Elasticsearch 조각 인덱스 매핑 (llmhub은 코드로 생성하는 걸 JSON으로 추출) |
| `README.md` | 이 문서 — 옮기는 법 + 설계 근거 용어집 + 불변식 체크리스트 |

**들지 않은 것(범위 밖):** JPA 엔티티, `application.yml`, docker-compose/인프라. 옹기종기에서 팀과 새로 쌓는다.

## PostgreSQL 스키마 한눈에

- `app_user` — 로컬 사용자 식별(Keycloak sub 참조). **역할 미저장**(S3).
- `document` — 원본 문서 레코드. **접근태그의 유일 원천**(S18). `doc_key` 재업로드로 갱신(S17).
- `chat_session` / `chat_message` — 사용자 소유 대화. 사용자 삭제 시 cascade.
- `audit_log` — 감사 로그. **FK 없음**, 이력과 완전 별도(S5). `outcome`으로 완료/취소/오류 구분.

문서 1개(PG `document`) = 조각 여러 개(ES). 조각은 `document_id`로 상위와 연결되고, 접근태그는 상위에서 복사된 사본이다.

## 옮기는 법

1. **PostgreSQL 스키마**: `migration/*.sql` 4개를 옹기종기 백엔드의 Flyway 경로
   (`backend/src/main/resources/db/migration/` 또는 그 프로젝트의 동등 위치)에 **파일명 그대로** 복사한다.
   파일명 앞의 `V1`~`V4` 순번이 적용 순서다 — 이름을 바꾸지 않는다.
   - Flyway 의존성(`flyway-core`, `flyway-database-postgresql`)과 PostgreSQL 드라이버가 있어야 앱 기동 시 자동 적용된다.
   - SQL 헤더 주석이 참조하는 `docs/03-data-model.md`는 이 번들에 동봉돼 있고(아래 참조), `S3·S5·S17·S18` 등
     결정 코드는 이 README의 **용어집**이 뜻을 채운다.
2. **설계 문서**: `03-data-model.md`를 옹기종기 `docs/`에 둔다(예: `docs/03-data-model.md`). 스키마의 "왜"가 여기 있다.
3. **Elasticsearch 인덱스**: 색인 시작 **전에** 인덱스를 만든다. 두 방법 중 하나.
   - (a) `es-chunk-index.json`을 인덱스 생성 바디로 그대로 사용:
     ```bash
     curl -X PUT "http://localhost:9200/<인덱스명>" \
       -H 'Content-Type: application/json' \
       --data-binary @es-chunk-index.json
     ```
   - (b) llmhub처럼 앱 코드에서 생성 — 이 매핑(필드/타입/analyzer/similarity)을 그대로 재현하면 된다.

### ES 매핑 주의 2가지 (JSON엔 주석을 못 달아 여기 적는다)

1. **`analyzer: "nori"`** 는 Elasticsearch에 **analysis-nori 플러그인**이 설치돼 있어야 동작한다. 공식 이미지엔
   없으므로, 플러그인을 구워 넣은 커스텀 이미지를 쓴다(llmhub `docker/elasticsearch/Dockerfile` 참고).
   배포 언어가 한국어가 아니면 이 값을 그 언어 분석기로 바꾼다(S15) — 그러면 **새 인덱스**가 필요하다.
2. **`dims: 1024`** 는 **임베딩 모델의 출력 차원**이다. 색인·검색에 쓰는 임베딩 모델이 같아야 하고(불변식 #5, S8-4),
   모델이 바뀌어 차원이 달라지면 이 값을 고치고 **인덱스를 새로 만들어야** 한다 — dense_vector 차원은 인덱스
   생성 시점에 고정되어 나중에 못 바꾼다. (llmhub 기본값은 bge-m3 = 1024)

## 설계 근거 용어집 (02-decisions.md 없이도 읽히게)

SQL과 `03-data-model.md`에 등장하는 결정 코드의 한 줄 뜻. 원문 상세는 llmhub `docs/02-decisions.md`(S/E)와
`docs/04-nonfunctional.md`(PERF/REL/R), 요구사항 `docs/requirements/REQ-AUDIT-logging.md`(R/L)에 있다.

| 코드 | 뜻 |
|---|---|
| **S2** | 채팅은 세션 단위. 메시지는 세션에 속한다(`chat_session`/`chat_message`). |
| **S3** | 역할은 Keycloak이 부여하고 **요청 시점에 접근태그로 변환**한다. DB에 역할을 저장하지 않는다(이중 관리 방지). |
| **S4** | 접근태그 확정은 **앞단 게이트에서만**. 검색·RAG 계층은 태그를 소비만 하고 권한을 판단하지 않는다. |
| **S5** | `audit_log`는 대화 이력과 **완전 별도, FK 없음**. 사용자·세션 삭제와 무관하게 남는다. `requester_id`는 값 복사. |
| **S6** | 근거(sources)는 **서버 검색 결과에서만** 나온다(LLM 출력 파싱 금지). `chat_message.sources_json` = 응답 시점 근거 스냅샷. |
| **S8-3** | 부분 색인된 신버전 조각이 구버전과 함께 검색되면 안 된다 → 임베딩을 **전량 완료한 뒤** 색인한다(S17과 함께). |
| **S8-4** | 색인·검색 임베딩 모델은 **동일**해야 한다. 모델은 설정으로 고정하고 조각 메타(`embedding_model`)에 기록한다. |
| **S11** | BM25 + 벡터를 **단일 쿼리**로 결합한다 — 그래서 `chunk_text`와 `embedding`이 한 조각 문서에 함께 있다. |
| **S15** | BM25 분석기 = **배포 언어 설정**. v0는 한국어 `nori`. 바꾸면 새 인덱스가 필요하다. |
| **S16** | 업로드 **원본을 보관**(`document.original_path`)한다. 재색인은 이 원본을 다시 읽어 수행한다. |
| **S17** | 같은 `doc_key` 재업로드 = `document` 행 갱신. 신버전 색인 성공 후 **`indexing_run_id`로 구버전 조각을 삭제**한다. |
| **S18** | 접근태그의 **유일 원천은 `document.access_tags`**. ES 조각의 `access_tags`는 거기서 복사된 사본이다. |
| **E5** | 감사 기록의 범위(무엇까지 남길지)는 설정값이다. |
| **E6** | 조각에 **신규 메타 필드 추가는 재색인 불요**. |
| **E9** | `embedding_model`/`embedding_dim`으로 재색인 대상을 식별한다. |
| **E16** | 언어(분석기)는 배포 설정이다 — 도메인 중립이되 **언어 중립은 아니다**. |
| **PERF-3** | 하이브리드 검색을 단일 쿼리로 수행(성능 근거). |
| **REL-4** | 운영자가 분석기·상한 등 튜너블을 배포마다 바꿀 수 있어야 한다. |
| **R-5 / REQ-AUDIT** | 감사는 완료뿐 아니라 **취소·오류에도** 기록한다 → `audit_log.outcome`(COMPLETE/CANCELLED/ERROR). |
| **L-5** | `document.uploaded_by`를 사용자 삭제와 독립시킨다 → FK `ON DELETE SET NULL`(사용자 삭제 시 문서는 남김). |

## 불변식 (옹기종기에서도 테스트로 강제할 것)

`03-data-model.md`의 계약. 구현 시 테스트로 못 박는다.

1. 모든 조각은 필수 메타데이터 7종을 빠짐없이 가진다.
2. 조각의 `access_tags`는 상위 `document`의 `access_tags`와 일치한다(사본).
3. `audit_log`에는 어떤 FK도 없다.
4. 같은 `doc_key`로 두 번 색인 후, 구버전 조각은 존재하지 않는다.
5. 색인에 쓴 `embedding_model`과 검색에 쓰는 임베딩 모델은 동일하다.

## 경고

- 이 번들은 **llmhub의 특정 시점 스냅샷**이다. 이후 llmhub 스키마가 바뀌어도 자동으로 동기화되지 않는다(일회성 이관).
- 비밀정보 없음 — 스키마와 설계 문서뿐이다. 키·토큰·비밀번호는 담기지 않았다.
- SQL·매핑은 llmhub의 스택(PostgreSQL 17 + Elasticsearch 9.x + nori)을 전제로 검증됐다. 옹기종기가 같은 스택이면 그대로,
  다르면 타입·분석기·차원을 맞춰 조정한다.
