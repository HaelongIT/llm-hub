# llmhub DB 설계 이관 번들 → 옹기종기

llmhub의 **DB 스키마 설계**를 옹기종기(onggijonggi) 프로젝트로 들고 가기 위한 자체 포함 번들이다.

> ## ⚠️ 먼저 읽을 것 — 번들 안에서 스키마가 **진화했다**
> 이 번들은 처음엔 "llmhub 스냅샷을 그대로 복사"하는 용도였다. 그 뒤 **14라운드에 걸친 설계 리뷰**를
> 거치며 옹기종기용으로 상당히 바뀌었다(비동기 색인 상태 추적 · 부서 스코프 · 소프트 삭제 · 문서 버전
> 이력 · 서로게이트 키 등).
>
> **→ `migration/*.sql`은 이제 출발점이 아니라 "llmhub 원본 참고"다. 최종 형태는 `final-schema.md`다.**

## 이 번들에 든 것

| 파일 | 무엇 | 상태 |
|---|---|---|
| **`final-schema.md`** | **★ 14라운드 최종 스키마 한 장.** 필드마다 역할·근거·등급(A/B/C) 병기 | **정본** |
| **`review-response.md`** | 설계 리뷰 논의 정본(라운드 1~14, 종료). **왜 그렇게 정했나가 여기 있다** | **정본** |
| `03-data-model.md` | llmhub 설계 본체: PG 5테이블 + ES 필드 + 불변식 5가지 | llmhub 원본 |
| `es-chunk-index.json` | Elasticsearch 조각 인덱스 매핑 | llmhub 원본(+2필드 추가 필요, `final-schema.md`) |
| `migration/V1~V4.sql` | llmhub PostgreSQL 마이그레이션 | **llmhub 원본 — 그대로 적용 금지** |
| `README.md` | 이 문서 — 옮기는 법 + 이름 규칙 + 근거 용어집 | — |

**들지 않은 것(범위 밖):** JPA 엔티티, `application.yml`, docker-compose/인프라. 옹기종기에서 팀과 새로 쌓는다.

## 읽는 순서

1. **`final-schema.md`** — 최종 형태. 여기서 스키마를 조립한다.
2. **`review-response.md` §0 · §0-2** — 판정 두 렌즈. *물려받은 근거가 여기서도 성립하나* /
   *지금 안 정하면 나중에 넣을 수 있나.* **새 항목이 생기면 이 둘로 판정하면 되므로 14라운드를 다시
   돌 필요가 없다.**
3. **`review-response.md` §3** — 착수 전(A·B) / 나중에(C) 체크리스트.
4. **`review-response.md` §4** — 열린 질문 상태(둘뿐, 둘 다 조직 사실).
5. 필요할 때만 — **§1·§2·§5는 이력**이다(llmhub 코드 검증 → 라운드별 정정 과정). 처음부터 순서대로
   읽으면 시간을 버린다.
6. `03-data-model.md` · `migration/*.sql` — llmhub이 **원래 어땠나**를 볼 때.

## 이름 규칙 — 이 번들의 모든 이름은 llmhub 원본이다

**옹기종기는 자기 용어집으로 이름만 바꿔 쓴다. 이름이 달라도 역할·제약·등급이 같으면 같은 설계다.**
아래는 논의 중 **실제로 관찰된** 매핑이고, **여기 없는 이름은 팀 용어집을 따른다**(추측으로 채우지 않는다).

| llmhub | 옹기종기(관찰됨) |
|---|---|
| `document` | `doc` |
| `access_tags` | `acc_tag` |
| `chat_session` / `chat_message` | `chat_sess` / `chat_msg` |
| `audit_log` | `adt_log` |
| `requester_id` | `req_id` |
| `sources_json` | `src_json` |
| `outcome` | `otc` |
| `embedding_model` | `emb_mdl` |
| `indexing_run_id` | `idx_run_id` |
| `original_path` | `org_path` |
| `03-data-model.md`(문서) | `04_data.md` |

`doc_key`는 양쪽이 같다.

## PostgreSQL 스키마 한눈에

- `app_user` — 로컬 사용자 식별(Keycloak sub 참조). **역할 미저장**(S3).
- `document` — 원본 문서 레코드. **접근태그의 유일 원천**(S18). `doc_key` 재업로드로 갱신(S17).
- `chat_session` / `chat_message` — 사용자 소유 대화. 사용자 삭제 시 cascade.
- `audit_log` — 감사 로그. **FK 없음**, 이력과 완전 별도(S5). `outcome`으로 완료/취소/오류 구분.

문서 1개(PG `document`) = 조각 여러 개(ES). 조각은 `document_id`로 상위와 연결되고, 접근태그는 상위에서 복사된 사본이다.

## 옮기는 법

1. ~~**PostgreSQL 스키마**: `migration/*.sql` 4개를 옹기종기 Flyway 경로에 **파일명 그대로** 복사한다.~~
   → **철회. 그대로 적용하면 안 된다.**
   그 SQL엔 14라운드 결정이 **하나도 반영돼 있지 않다** — `department`·`status`·`status_at`·
   `pending_idx_run_id`/`cur_idx_run_id`·`acc_tag_ver`·`deleted_at`/`deleted_by`·`document_version`·
   임베딩 지문이 전부 없고, `uploaded_by`가 FK `SET NULL`이며(값 복사로 바뀜) 유니크가 전역이다
   (부분 유니크로 바뀜).
   - **최종 형태는 `final-schema.md`다.** 거기서 조립해 **옹기종기 용어집 이름으로 새로 쓴다.**
   - `migration/*.sql`은 **llmhub이 원래 어땠나를 대조할 때만** 본다. SQL 헤더 주석이 참조하는
     `docs/03-data-model.md`도 같은 성격이고, `S3·S5·S17·S18` 등 결정 코드는 아래 **용어집**이 뜻을 채운다.
   - Flyway 의존성(`flyway-core`, `flyway-database-postgresql`)과 PostgreSQL 드라이버가 있어야 앱 기동 시
     자동 적용된다 — 이건 그대로 유효하다.
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

`03-data-model.md`의 계약. 구현 시 테스트로 못 박는다. **단 2·5번은 리뷰를 거쳐 갱신됐다.**

1. 모든 조각은 필수 메타데이터 7종을 빠짐없이 가진다. → **지문·`acc_tag_ver`가 추가돼 9종**
   (`final-schema.md` ES 절).
2. 조각의 `access_tags`는 상위 `document`의 `access_tags`와 일치한다(사본).
   > **⚠️ 갱신(4라운드)** — **항상 참이 아니다.** `acc_tag_ver`가 **일치할 때만** 참이다. "ES 먼저,
   > PG 나중" 갱신 중과 동시 재색인 중에는 일시적으로 어긋나고, 복구 수단은 `acc_tag_ver` 정합성
   > 검사다(`review-response.md` §5-1·§5-1a). **검증 스크립트에 이 캐벗을 반영할 것.**
3. `audit_log`에는 어떤 FK도 없다. — 그대로 유효.
4. 같은 `doc_key`로 두 번 색인 후, 구버전 조각은 존재하지 않는다.
   > **보강** — 이제 스코프가 `(department, doc_key)`이고, 이 불변식을 지키는 장치는
   > `cur_idx_run_id`/`pending_idx_run_id` + **고아 조각 스위퍼**다(§5-1a·§5-8).
5. 색인에 쓴 `embedding_model`과 검색에 쓰는 임베딩 모델은 동일하다.
   > **⚠️ 갱신(10라운드)** — **이름 비교로는 성립하지 않는다.** 같은 태그로 가중치가 바뀔 수 있으므로
   > **지문(fingerprint) 비교**로 판정한다. 지문은 조각 필드라 **착수 전에 넣어야 한다** — 나중에
   > 넣으면 그 사이 색인된 조각이 어떤 가중치로 만들어졌는지 **영원히 알 수 없다**(소급 불가).

## 경고

- **`migration/*.sql`과 `03-data-model.md`는 llmhub 원본이고, 최종 형태가 아니다.** 최종은
  `final-schema.md` + `review-response.md`다. 이 둘을 섞어 읽으면 14라운드에 뒤집힌 결정을 그대로
  구현하게 된다(예: `uploaded_by` FK `SET NULL`, 전역 유니크, 결정적 id 유도).
- 이 번들은 **llmhub의 특정 시점 스냅샷**이다. 이후 llmhub 스키마가 바뀌어도 자동으로 동기화되지 않는다(일회성 이관).
- 비밀정보 없음 — 스키마와 설계 문서뿐이다. 키·토큰·비밀번호는 담기지 않았다.
- SQL·매핑은 llmhub의 스택(PostgreSQL 17 + Elasticsearch 9.x + nori)을 전제로 검증됐다. 옹기종기가 같은 스택이면 그대로,
  다르면 타입·분석기·차원을 맞춰 조정한다.
