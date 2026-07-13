# llmhub

도메인 중립 RAG 챗 런타임. 회사별 설치형 배포를 전제로, 인증·권한·감사가 내장된 문서 검색 기반 채팅 시스템.

> **상태: v0 완료.** 색인→로그인→질문→근거 검색→스트리밍 응답→감사가 end-to-end로 동작한다. 코드리뷰 2회 + 실검증(뮤테이션 테스트·살아 있는 스택·Playwright E2E)을 거쳤다.
>
> **프로젝트를 처음 본다면 → [docs/syj/onboarding.md](docs/syj/onboarding.md)** (무엇이고 왜 이렇게 만들었는지 설명하는 팀 온보딩 문서).

## 이 시스템이 하는 일

관리자가 문서(txt·pdf·hwp·docx)를 올리면, 시스템이 문서를 잘게 쪼개 의미 벡터로 만들어 저장한다. 사용자가 질문하면 **질문과 관련된 조각만** 찾아서 그 내용을 근거로 LLM이 답한다. 이때 사용자의 역할에 따라 **볼 수 있는 문서만** 검색된다. 모든 질문과 답변은 감사 로그에 남는다.

핵심은 "LLM이 아무 말이나 하지 않는다"는 것이다. 답변에 붙는 근거(`sources`)는 LLM이 만든 것이 아니라 **서버가 실제로 검색한 조각**에서 나온다.

---

# 1. 시작하기 전에 — 필요한 환경

설치가 안 된 게 하나라도 있으면 중간에 막힌다. 먼저 다 갖춰두자.

| 필요한 것 | 버전 | 왜 필요한가 | 설치 확인 |
|---|---|---|---|
| **Java** | 21 | 코어(백엔드)가 Spring Boot 21 기준 | `java -version` |
| **Node.js** | 22 이상 | 프론트엔드(Next.js). 테스트가 Node 22의 기능을 씀 | `node -v` |
| **Docker Desktop** | 최신 | PostgreSQL·Elasticsearch·Keycloak·LiteLLM을 컨테이너로 띄움 | `docker compose version` |
| **bash** | — | 셋업 스크립트와 커밋 훅이 전부 bash다 | `bash --version` |
| **Ollama** | 최신 | LLM과 임베딩 모델을 로컬에서 돌림 | `ollama --version` |
| **git** | 최신 | — | `git --version` |

### 운영체제별로 주의할 것

**Windows** — `bootstrap-dev.sh` 같은 스크립트와 커밋 훅이 bash를 전제한다. **Git Bash에서 실행한다** (Git for Windows에 함께 설치된다). PowerShell·cmd에서는 `set -a; . ./.env` 같은 명령이 동작하지 않는다.

**macOS / Linux** — 추가 셸 설정은 필요 없다. 다만 Docker Desktop for Mac은 **VM 메모리 상한이 고정**이라 기본값이면 부족할 수 있다. Docker Desktop → Settings → Resources에서 **메모리를 최소 10GB 이상**으로 올린다. compose가 거는 메모리 상한 합계만 약 8GB이고, 백엔드 테스트(Testcontainers)는 Elasticsearch 컨테이너를 별도로 더 띄운다.

### 하드웨어

RAM **16GB 이상**을 가정한다. `.env`의 `*_MEM_LIMIT` 값들이 그 기준으로 잡혀 있다. 그보다 작다면 `.env`에서 상한을 낮춰야 하는데, Elasticsearch(기본 2GB)를 너무 줄이면 기동에 실패한다.

---

# 2. 처음 셋업 — 순서대로 따라하기

터미널을 열고 저장소 루트에서 시작한다. **Windows라면 Git Bash를 쓴다.**

## 단계 1 — 저장소를 받고 커밋 훅을 켠다

```bash
git clone <저장소 주소>
cd llmhub
git config core.hooksPath .githooks
```

`core.hooksPath`는 **필수다.** 이 훅이 커밋 전에 테스트를 돌리고, 비밀번호가 든 파일(`.env`)이나 테스트를 무력화하는 코드가 실수로 커밋되는 것을 막는다.

> 훅이 켜졌는지 확인: `git config core.hooksPath` → `.githooks`가 나와야 한다.

## 단계 2 — 설정 파일(`.env`)을 만든다

```bash
cp .env.example .env
```

`.env`는 **커밋되지 않는다**(`.gitignore`). 비밀번호가 들어가기 때문이다. `.env.example`에는 `change-me` 같은 자리표시자가 들어 있으니, 아래 값들을 **직접 채운다.**

| 채워야 할 값 | 무엇인가 | 어떻게 정하나 |
|---|---|---|
| `POSTGRES_PASSWORD` | DB 비밀번호 | 아무 값이나. 개발용이면 단순해도 된다 |
| `ES_PASSWORD` | Elasticsearch 비밀번호 | 아무 값이나 |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak 관리자 콘솔 로그인 | 아무 값이나 |
| `KEYCLOAK_CLIENT_SECRET` | 프론트엔드가 Keycloak에 자신을 증명하는 열쇠 | 아무 값이나. **단계 5의 스크립트가 이 값을 Keycloak에 등록한다** |
| `DEV_USER_PASSWORD` | 개발용 일반 사용자 계정 비밀번호 | 아무 값이나 |
| `DEV_ADMIN_PASSWORD` | 개발용 관리자 계정 비밀번호 | 아무 값이나 |
| `LITELLM_API_KEY` | LLM 게이트웨이 인증 키 | 아무 값이나 |
| `AUTH_SECRET` | 프론트 세션 쿠키 서명 키 | **반드시 랜덤값.** `openssl rand -base64 32` |

> 개발 환경이라 "아무 값이나"라고 썼지만, **운영 배포에서는 전부 강한 랜덤값이어야 한다.**

## 단계 3 — LLM 모델을 받는다

이 시스템은 호스트에서 도는 Ollama를 LLM·임베딩 백엔드로 쓴다. 모델을 미리 받아둔다. (몇 GB라 시간이 걸린다.)

```bash
ollama pull bge-m3      # 임베딩 — 문서를 벡터로 바꾼다 (1024차원)
ollama pull qwen3:8b    # 채팅 — 실제 답변을 생성한다
```

> `bge-m3`의 1024차원은 `.env`의 `EMBEDDING_DIM`과 **반드시 일치해야 한다.** 다른 임베딩 모델로 바꾸려면 `.env`의 `EMBEDDING_MODEL`·`EMBEDDING_DIM`과 `docker/litellm/config.yaml`을 함께 고쳐야 하고, 이미 색인한 문서는 전부 재색인해야 한다(S8-4).

## 단계 4 — 인프라를 띄운다

```bash
docker compose up -d
```

PostgreSQL · Elasticsearch(한국어 분석기 nori 포함) · Keycloak · LiteLLM 네 개가 뜬다. Elasticsearch 이미지는 처음 한 번 **직접 빌드**하므로(nori 플러그인 설치) 몇 분 걸린다.

전부 `healthy`가 될 때까지 기다린다. 확인:

```bash
docker compose ps
```

> **자주 겪는 함정:** 최초 기동 때 PostgreSQL이 Keycloak용 DB를 만드는 도중에 compose가 "healthy"로 판단해, Keycloak이 먼저 붙으려다 실패할 수 있다. 그럴 땐 **`docker compose up -d`를 한 번 더 실행**하면 붙는다.
>
> 코어(백엔드)와 프론트엔드는 이 명령으로 **뜨지 않는다.** 의도된 동작이다 — 개발 중에는 소스에서 직접 돌려야 코드 수정이 즉시 반영된다.

## 단계 5 — Keycloak에 계정과 클라이언트를 만든다

Keycloak은 로그인을 담당하는 인증 서버다. 개발용 계정과 프론트엔드 클라이언트를 만들어야 한다. 비밀번호·시크릿은 저장소에 커밋하지 않으므로(SEC-3), 설정 파일이 아니라 이 스크립트가 `.env` 값을 읽어 만든다.

```bash
./docker/keycloak/bootstrap-dev.sh
```

여러 번 실행해도 안전하다. 이 스크립트가 만드는 것:

| 계정 | 역할 | 볼 수 있는 문서(접근 태그) | 문서 색인 |
|---|---|---|---|
| `dev-user` | USER | `public` | 불가 |
| `dev-admin` | ADMIN | `public`, `restricted` | 가능 |

비밀번호는 `.env`의 `DEV_USER_PASSWORD` · `DEV_ADMIN_PASSWORD`다.

## 단계 6 — 코어와 프론트엔드를 실행한다

**터미널 두 개**가 필요하다. 둘 다 저장소 루트에서 시작하고, 각각 `.env`를 환경변수로 읽어들인다.

```bash
# ── 터미널 A — 코어 (WebFlux, :8080)
set -a; . ./.env; set +a
cd backend && ./gradlew --no-daemon bootRun
```

```bash
# ── 터미널 B — BFF + UI (Next.js, :3000)
set -a; . ./.env; set +a
cd frontend && npm install && npm run dev
```

`set -a; . ./.env; set +a`는 `.env`의 값들을 현재 셸의 환경변수로 만든다. **이 줄을 빼먹으면** 코어가 DB 비밀번호를 몰라 기동에 실패하거나, 프론트가 Keycloak을 찾지 못한다.

> `--no-daemon`이 **필요하다.** 살아 있는 Gradle 데몬은 나중에 export한 환경변수를 물려받지 못해, 설정이 조용히 기본값으로 떨어진다. 원인을 찾기 어려운 종류의 버그다.

## 단계 7 — 접속한다

브라우저에서 **http://localhost:3000** 을 연다. "Keycloak으로 로그인" 버튼이 보이면 성공이다. `dev-admin` 또는 `dev-user`로 로그인한다.

로그인하면 왼쪽에 대화 목록, 오른쪽에 채팅 화면이 뜬다. 사용자명 옆의 칩(`USER` / `ADMIN`)이 내 역할이다.

**아직 질문해도 답이 안 나온다.** 검색할 문서가 하나도 없기 때문이다. → **3장으로 간다.**

## 셋업이 안 될 때

| 증상 | 원인과 해결 |
|---|---|
| `./gradlew: permission denied` | 실행 비트 문제. 최신 `main`을 받으면 해결됐다. 그래도 나면 `chmod +x backend/gradlew` |
| `bad interpreter: ^M` | 스크립트 줄바꿈이 CRLF다. `git config core.autocrlf false` 후 다시 클론 |
| 코어가 `Connection refused`로 죽음 | 인프라가 아직 안 떴다. `docker compose ps`로 전부 `healthy` 확인 후 재시도 |
| 로그인 후 바로 로그아웃됨 | `.env`의 `KEYCLOAK_CLIENT_SECRET`과 Keycloak에 등록된 값이 다르다. `./docker/keycloak/bootstrap-dev.sh` 재실행 |
| 질문하면 `index_not_found` 오류 | 색인된 문서가 0건이다. 정상이다 — 3장대로 문서를 올린다 |
| Elasticsearch가 계속 죽음 | 메모리 부족. Docker Desktop 메모리를 올리거나 `.env`의 `ES_MEM_LIMIT`를 조정 |
| 임베딩 단계에서 오류 | Ollama가 안 떠 있거나 모델을 안 받았다. `ollama list`로 확인 |

---

# 3. 써보기 — 문서를 올리고 질문한다

## 먼저 알아야 할 것: 업로드 화면은 없다

**v0에는 관리자 UI가 없다**(CLAUDE.md §7 — 의도적으로 스코프 밖). 문서 색인은 **API를 직접 호출**해서 한다. 아래 `curl` 예제를 그대로 따라하면 된다.

## 3-1. 관리자 토큰 받기

API를 호출하려면 로그인 토큰이 필요하다. `dev-admin`으로 토큰을 받는다.

```bash
set -a; . ./.env; set +a          # .env 값을 읽어온다

TOKEN=$(curl -s -X POST \
  "http://localhost:8081/realms/llmhub/protocol/openid-connect/token" \
  -d "client_id=llmhub-frontend" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d "grant_type=password" \
  -d "username=dev-admin" \
  -d "password=$DEV_ADMIN_PASSWORD" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

echo "${TOKEN:0:20}..."            # 값이 찍히면 성공
```

> **이 방법은 개발 환경 전용이다.** `bootstrap-dev.sh`가 이 로그인 방식(password grant)을 켜주기 때문에 동작한다. **운영 배포에서는 `bootstrap-prod.sh`가 이걸 꺼버린다** — 보안상 브라우저 로그인만 허용한다.
>
> 반드시 `client_id=llmhub-frontend`여야 한다. `admin-cli` 같은 다른 클라이언트로 받은 토큰은 코어가 **401로 거부**한다(audience 검증).

## 3-2. 문서 올리기

```bash
curl -X POST http://localhost:8080/api/index \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./사내규정.pdf;type=application/pdf" \
  -F "docKey=규정-2026" \
  -F "accessTags=public"
```

성공하면 이렇게 나온다:

```json
{"documentId":"...","indexingRunId":"...","chunkCount":12}
```

`chunkCount`는 문서가 몇 조각으로 쪼개졌는지다. 이 숫자가 0이 아니면 검색 가능한 상태다.

### 파라미터 세 개는 전부 필수다

| 파라미터 | 뜻 | 주의 |
|---|---|---|
| `file` | 올릴 파일 | **`;type=...`으로 MIME을 명시한다.** curl이 붙이는 기본값이 허용목록과 다르면 400이 난다 |
| `docKey` | 이 문서의 고유 키 (직접 정한다) | **같은 `docKey`로 다시 올리면 기존 문서가 교체된다.** 아래 3-4 참고 |
| `accessTags` | 누가 볼 수 있는가 | 쉼표로 구분. `public` 또는 `public,restricted` |

파일명이 그대로 문서 제목이 된다. 별도 `title` 파라미터는 없다.

### 올릴 수 있는 파일

| 확장자 | 지정할 MIME 타입 |
|---|---|
| `.txt` | `text/plain` |
| `.pdf` | `application/pdf` |
| `.hwp` | `application/x-hwp` 또는 `application/haansofthwp` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |

**확장자와 MIME이 둘 다 맞아야** 통과한다. 추가로 파일 내용의 첫 바이트를 검사해서 실행파일이 위장한 경우를 거부한다.

최대 크기는 **50MB**다. 바꾸려면 `MAX_UPLOAD_BYTES` 환경변수를 설정한다 — `.env.example`에는 없는 키이니 필요할 때 `.env`에 직접 추가한다.

## 3-3. 접근 태그 — 누가 무엇을 보는가

이게 이 시스템의 핵심 보안 장치다. 문서마다 태그를 붙이고, 사용자는 자기 역할이 가진 태그의 문서만 검색된다.

| 역할 | 가진 태그 | 즉, 볼 수 있는 문서 |
|---|---|---|
| USER | `public` | `accessTags`에 `public`이 있는 문서 |
| ADMIN | `public`, `restricted` | 둘 중 하나라도 있는 문서 |

이 매핑은 `backend/src/main/resources/application.yml`의 `llmhub.auth.role-tags`에서 바꿀 수 있다.

> **⚠ 태그에 오타를 내면 아무도 그 문서를 못 본다.** 시스템은 태그 값을 검증하지 않는다. `accessTags=restrcited`(오타)로 올리면 업로드는 성공하지만, 그 태그를 가진 역할이 없으므로 **어떤 사용자에게도 검색되지 않는 유령 문서**가 된다. 오타를 냈다면 같은 `docKey`로 올바른 태그를 넣어 다시 올린다(교체된다).

## 3-4. 문서 교체하기

**같은 `docKey`로 다시 올리면 된다.** 별도 명령이 없다.

```bash
curl -X POST http://localhost:8080/api/index \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./사내규정_v2.pdf;type=application/pdf" \
  -F "docKey=규정-2026" \
  -F "accessTags=public"
```

새 조각을 **먼저 다 색인한 뒤에** 구버전 조각을 지운다. 중간에 실패해도 구버전이 그대로 남아 있어 검색이 끊기지 않는다.

## 3-5. 문서 삭제하기 — **v0에는 없다**

**문서를 지우는 API가 없다.** `DELETE` 엔드포인트가 존재하지 않고, `docKey`로도 `documentId`로도 지울 수 없다. 요구사항(`docs/requirements/REQ-IDX-indexing.md`)이 "업로드 → 저장 → 재업로드 시 교체"까지만 정의하고 삭제를 다루지 않기 때문이다.

당장 문서를 안 보이게 해야 한다면 **아무도 갖지 않은 태그로 교체**하는 우회가 가능하다:

```bash
curl -X POST http://localhost:8080/api/index \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./사내규정.pdf;type=application/pdf" \
  -F "docKey=규정-2026" \
  -F "accessTags=archived"
```

`archived` 태그를 가진 역할이 없으므로 검색에서 사라진다. 다만 **데이터는 남아 있다** — 진짜 삭제가 아니다. 정식 삭제 기능은 [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md)에 올려두었다.

## 3-6. 재색인 — 임베딩 모델이나 청킹 방식을 바꿨을 때

임베딩 모델을 바꾸면 **기존 문서의 벡터는 새 모델의 벡터와 비교할 수 없다.** 그래서 다시 색인해야 한다. 파일을 다시 올릴 필요는 없다 — 시스템이 원본을 보관하고 있다.

**재색인이 필요한 문서 찾기:**

```bash
curl -s http://localhost:8080/api/index/stale -H "Authorization: Bearer $TOKEN"
```

```json
[{"docKey":"규정-2026","filename":"사내규정.pdf",
  "embeddingModel":"bge-m3","chunkingVersion":"v1","reason":"MODEL"}]
```

`reason`은 왜 대상인지 알려준다: `MODEL`(임베딩 모델이 바뀜) · `CHUNKING`(청킹 방식이 바뀜) · `MODEL_AND_CHUNKING`(둘 다).

**재색인 실행:**

```bash
curl -X POST "http://localhost:8080/api/index/reindex?docKey=규정-2026" \
  -H "Authorization: Bearer $TOKEN"
```

접근 태그는 **문서에 저장된 현재 값을 그대로 쓴다.** 재색인 요청으로 권한을 바꿀 수 없다(S18).

> 이건 "전체 문서 목록" API가 아니다. 재색인이 필요한 문서만 나온다. 전체 목록 조회 API는 v0에 없다.

## 3-7. 질문하기

브라우저(http://localhost:3000)에서 로그인하고 질문을 입력하면 된다.

- 답변이 **한 글자씩 흘러나온다**(스트리밍). 중간에 멈출 수 있다.
- 답변 위에 **근거(sources)** 가 붙는다. 이건 LLM이 지어낸 게 아니라 서버가 실제로 검색해온 문서 조각이다.
- 왼쪽 사이드바에서 **대화를 새로 만들거나 삭제**할 수 있다. 대화 이력은 저장된다.
- 질문은 **최대 4000자**다.

**`dev-user`와 `dev-admin`으로 각각 로그인해서 같은 질문을 해보면** 접근 태그가 실제로 작동하는 걸 볼 수 있다. `restricted` 문서를 올려두면 `dev-admin`은 그 내용으로 답하고, `dev-user`는 그 문서가 아예 없는 것처럼 답한다.

## API 한눈에 보기

| 기능 | 요청 | 필요 역할 |
|---|---|---|
| 문서 올리기 / 교체 | `POST /api/index` (multipart: `file`, `docKey`, `accessTags`) | ADMIN |
| 재색인 | `POST /api/index/reindex?docKey=...` | ADMIN |
| 재색인 대상 조회 | `GET /api/index/stale` | ADMIN |
| 문서 삭제 | **없음** | — |
| 전체 문서 목록 | **없음** | — |
| 질문(스트리밍) | `POST /api/chat/stream` — `{"sessionId": null, "question": "..."}` | 로그인 |
| 대화 목록 | `GET /api/sessions` | 로그인 |
| 대화 생성 | `POST /api/sessions` | 로그인 |
| 대화 이력 | `GET /api/sessions/{id}/messages` | 로그인 |
| 대화 삭제 | `DELETE /api/sessions/{id}` | 로그인 |

**남의 대화에 접근하면 403이 아니라 404가 돌아온다.** 존재 여부 자체를 숨긴다.

클라이언트(브라우저)는 프론트엔드(BFF)만 호출한다. 코어·Elasticsearch·PostgreSQL·LiteLLM은 내부망 전용이다(SEC-1). 위 표의 코어 API를 직접 치는 건 개발·운영 작업용이다.

---

# 4. 코드는 어떻게 생겼나 — 디렉터리 구조

## 전체

```
llmhub/
├── backend/                    코어 — Spring Boot WebFlux (:8080)
├── frontend/                   BFF + UI — Next.js (:3000). 브라우저가 닿는 유일한 곳
├── docker/                     인프라 서비스 설정 (소스는 없다 — 설정뿐)
│   ├── elasticsearch/          nori 플러그인을 얹는 2줄짜리 Dockerfile
│   ├── keycloak/               realm 정의 + 부트스트랩 스크립트 (dev / prod)
│   ├── litellm/                게이트웨이 모델 라우팅 설정
│   └── postgres/init/          최초 기동 시 Keycloak용 DB 생성
├── docs/                       거버넌스·명세·지식 (→ 6장)
├── .githooks/pre-commit        커밋 전 테스트·비밀정보·문서잠금 검사
├── docker-compose.yml          운영 기준. 고객에게 나가는 산출물 그대로
├── docker-compose.override.yml 개발용 완화. `docker compose up`에 자동으로 얹힌다
├── .env.example                설정 템플릿 (`.env`는 커밋되지 않는다)
└── CLAUDE.md                   프로젝트 헌법
```

## 코어 — `backend/src/main/java/com/llmhub/`

**패키지가 곧 모듈이고, 모듈이 곧 요구사항 문서다.** CLAUDE.md §5의 모듈 지도와 1:1로 대응한다.

```
idx/       색인    업로드 → 추출 → 청킹 → 임베딩 → 저장 · 재색인/교체    REQ-IDX
auth/      인증    JWT 검증 · 역할→접근태그 확정 (앞단 게이트)          REQ-AUTH
search/    검색    하이브리드(BM25+벡터) · 태그 필터 · sources 생성      REQ-SEARCH
chat/      채팅    ChatClient 오케스트레이션 · SSE · 세션·이력           REQ-CHAT
audit/     감사    감사 로그 (독립 수명주기)                             REQ-AUDIT
common/    공통    TraceId · 임베딩 클라이언트 · ES 클라이언트 · 사용자
```

모듈 안쪽은 대체로 같은 모양이 반복된다:

```
idx/
├── api/            HTTP 표면 (IndexController)
├── service/        도메인 로직 (IndexingService …)
├── persistence/    ← 블로킹 JPA를 여기에만 가둔다 (S13, E12)
├── config/         설정 바인딩 (IdxProperties …)
└── chunking/ parser/ storage/ index/ upload/    IDX 고유 부품
```

`persistence/`가 특히 중요하다. **WebFlux(논블로킹)와 JPA(블로킹)가 한 앱에 공존하기 때문에**, 블로킹 DB 접근은 반드시 이 계층에만 있어야 한다. 다른 데로 새면 스트리밍 흐름이 막힌다.

### 경계는 문서가 아니라 테스트다

`backend/src/test/java/com/llmhub/architecture/ModuleBoundaryTest.java`가 ArchUnit으로 의존 방향을 **빌드 실패로 강제한다.** 허용된 의존은 딱 둘뿐이다:

- 모든 모듈 → `common`
- `chat` → `search`, `audit` (오케스트레이션)

**그 외 모듈 간 의존은 전부 금지다.** 예컨대 `search`가 `auth`를 import하는 순간 빌드가 깨진다 — 검색 계층이 권한을 재판단하면 안 되기 때문이다(S4). 태그는 앞단 게이트가 확정하고 검색은 **소비만** 한다. 순환 의존도 함께 막는다.

Gradle 서브프로젝트로 쪼개는 대신 테스트로 지킨다. 규칙을 어기면 리뷰어가 아니라 **빌드가 먼저 잡는다.**

## BFF + UI — `frontend/`

```
app/
├── api/                        BFF 서버 라우트 — 브라우저는 여기만 부른다
│   ├── auth/[...nextauth]/     next-auth (Keycloak OIDC)
│   ├── chat/stream/            SSE 프록시 → 코어
│   ├── sessions/…              대화 CRUD 프록시 → 코어
│   └── health/
├── page.tsx  layout.tsx  globals.css
components/                     Chat · Sessions · Markdown · ThemeToggle
lib/                            순수 로직 + 단위 테스트 (*.test.ts가 나란히 있다)
types/
```

두 가지를 눈여겨볼 것.

**브라우저는 코어(:8080)를 직접 부르지 않는다.** 항상 `app/api/*`(BFF)를 거친다. 액세스 토큰은 서버측 세션에만 있고 브라우저로 내려가지 않는다.

**`lib/`은 전부 순수 함수다.** 스트림 파싱·마크다운·토큰 만료 계산 같은 로직을 UI에서 떼어냈기 때문에, 브라우저 없이 `node --test`로 즉시 검증된다. `lib/` 안에 소스와 `.test.ts`가 나란히 있는 이유다.

---

# 5. 외부 오픈소스를 어떻게 쓰는가

원칙은 한 줄이다. **받아서 쓴다. 고치지 않는다.**

이 저장소에는 **포크도, 소스 클론도, 벤더링(남의 코드를 우리 트리에 복사)도 한 건도 없다.** 모든 오픈소스는 두 갈래 중 하나로 들어온다.

## 갈래 1 — 컨테이너로 실행 (우리 코드 밖의 별도 프로세스)

PostgreSQL · Elasticsearch · Keycloak · LiteLLM. 공식 이미지를 그대로 받아 띄우고, 우리가 주는 건 **설정 파일과 환경변수뿐**이다.

| 서비스 | 우리가 주는 것 |
|---|---|
| PostgreSQL | 초기화 SQL (`docker/postgres/init/`) |
| Keycloak | realm 정의 (`realm-export.json`) + 부트스트랩 스크립트 |
| LiteLLM | 모델 라우팅 설정 (`docker/litellm/config.yaml`, 읽기 전용 마운트) |
| Elasticsearch | ↓ 아래 참고 |

**유일한 예외가 Elasticsearch다.** `image:`가 아니라 `build:`인데, 열어보면 Dockerfile이 2줄이다:

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.3
RUN bin/elasticsearch-plugin install --batch analysis-nori
```

공식 이미지에 **공식 한국어 형태소 플러그인**을 얹는 게 전부다(공식 이미지에 안 들어 있다). ES 소스를 건드린 게 아니라 지원되는 확장 지점을 쓴 것이다.

**Ollama는 아예 스택 밖에 있다.** compose에 서비스로 없고, 호스트에서 이미 돌고 있는 것을 `host.docker.internal:11434`로 부른다.

## 갈래 2 — 라이브러리로 링크 (빌드 도구가 받아온다)

- **코어(Gradle):** Spring Boot WebFlux · Spring Security · Spring AI · Flyway · Tika · hwplib · Elasticsearch 자바 클라이언트
- **프론트(npm):** Next.js · React · next-auth · Vercel AI SDK · zustand — **런타임 의존성이 7개뿐이다**

선언만 하고 코드는 만지지 않는다.

## LiteLLM은 무엇이고, 무엇이 아닌가

자주 오해가 생기는 지점이라 명시한다. **LiteLLM은 이 프로젝트의 경쟁 상대가 아니라 부품 하나다.**

LiteLLM이 이 스택에서 하는 일은 **채팅 모델 호출과 임베딩 호출, 딱 둘**이다. 코어는 `placeholder-chat-model` 같은 **모델명만** 던지고, 뒤에 Ollama가 있는지 OpenAI가 있는지 모른다. 덕분에 **모델 교체가 `config.yaml`과 `.env`에서 끝난다**(S8-1, E7).

반대로 LiteLLM이 해주지 않는 것이 이 프로젝트의 본체다 — 문서 색인, 하이브리드 검색, **역할에 따른 문서 단위 접근 통제**, 서버 검색 결과에서 생성하는 근거(`sources`), 세션·이력, 독립 수명주기의 감사 로그. LiteLLM에도 인증이 있지만 그건 *"이 API 키가 이 모델을 호출해도 되는가"*이고, 이 시스템이 강제하는 건 *"이 사람이 이 문서를 볼 수 있는가"*다. **층이 다르다.**

부품이라는 것은 실제로 증명된다 — LiteLLM을 걷어내고 OpenAI에 직결해도 코어는 거의 그대로다. Spring AI가 OpenAI 호환 규격을 쓰므로 `LITELLM_BASE_URL`만 바꾸면 된다.

## 오히려 직접 만든 것

라이브러리가 있는데도 안 쓴 곳이 두 군데다. **"라이브러리가 요구를 못 채우면 그 부분만 직접 짠다"이지, "라이브러리를 개조한다"가 아니다.**

- **마크다운 렌더러** (`frontend/lib/markdown.ts`) — `react-markdown`을 쓰지 않는다. LLM이 뱉은 텍스트를 HTML로 그리는 건 주입 위험이 있는 경로라, 파싱 범위를 우리가 통제한다.
- **하이브리드 검색** (`search/ElasticsearchChunkSearchRepository`) — Spring AI의 `VectorStore` 추상화가 **kNN 전용**이라 BM25+벡터 하이브리드가 불가능하다. 그래서 ES 자바 클라이언트를 직접 쓴다.

## 의존성을 늘리는 것은 거버넌스 사안이다

의존성이 적은 건 우연이 아니다. **CLAUDE.md §8이 사전 승인 목록 밖의 의존성 추가를 HALT 조건으로 걸어놨다** — 넣으려면 사람에게 물어야 한다. `backend/build.gradle.kts`도 *"의존성은 그것을 요구하는 TDD 사이클에서 추가한다"* 고 못박고 있다. 안 쓰는 스타터를 미리 넣으면 자동설정이 켜져 테스트가 깨지기도 한다.

## 버전 고정 현황

설치형 배포에서는 **"고객사에 무엇이 깔렸는가"를 되짚을 수 있어야** 한다. 현재 상태는 이렇다.

| 대상 | 고정 여부 |
|---|---|
| Elasticsearch `9.4.3` · Keycloak `26.7.0` | ✅ 정확한 태그 |
| 프론트 npm 의존성 | ✅ `package-lock.json` + `npm ci` (전이 의존성까지) |
| 코어 Gradle 의존성 | ✅ Spring Boot·Spring AI BOM + 명시 버전 |
| **LiteLLM `main-stable`** | ⚠ **움직인다** — 릴리스 태그가 아니라 `main` 브랜치 롤링 태그다 |
| PostgreSQL `17-alpine` · Node `22-alpine` | ⚠ 마이너가 움직인다 (패치 전용이라 위험은 낮다) |

`main-stable`이 움직이면 **같은 커밋을 배포해도 고객사마다 다른 소프트웨어가 돌아간다.** 첫 고객사 설치 전에 잠가야 한다 — [OQ-015](docs/OPEN-QUESTIONS.md)에 기록해 두었다.

같은 문제가 **임베딩 모델(`bge-m3`)에도 있고 그쪽이 더 나쁘다.** 운영자가 모델을 재pull하면 이름과 차원은 그대로인 채 가중치만 바뀌어, 색인된 벡터와 질문 벡터가 다른 공간에 놓인다 — **에러 없이 검색 품질만 무너진다.** [OQ-016](docs/OPEN-QUESTIONS.md)에서 다룬다.

---

# 6. 문서

작업 전 반드시 읽는다.

| 문서 | 내용 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 프로젝트 헌법 — 절대 규칙·스택·워크플로우 |
| [docs/00-overview.md](docs/00-overview.md) | 정체성·스코프·v0 완성 체크리스트 |
| [docs/01-architecture.md](docs/01-architecture.md) | 계층·모듈 경계·인터페이스 계약 |
| [docs/02-decisions.md](docs/02-decisions.md) | **구현이 반드시 지켜야 하는 결정(S)과 경계(E)** |
| [docs/03-data-model.md](docs/03-data-model.md) | 스키마와 불변식 |
| [docs/04-nonfunctional.md](docs/04-nonfunctional.md) | 보안·성능·운영 요구사항 |
| [docs/05-tdd-workflow.md](docs/05-tdd-workflow.md) | TDD 절차 (테스트 먼저) |
| [docs/08-autonomy-protocol.md](docs/08-autonomy-protocol.md) | **자율 진행 규칙** — 언제 멈추고 언제 계속하는가 |
| [docs/LEARNINGS.md](docs/LEARNINGS.md) | 누적된 교훈 — 세션 시작 시 읽는다 |
| [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md) | 보류된 질문 — 나중에 확인할 것들 |
| [docs/requirements/](docs/requirements/) | 모듈별 상세 요구사항 (= TDD 단위) |

### 커밋 훅

훅은 커밋 전에 테스트를 돌리고, 테스트 무력화 토큰과 비밀정보, 읽기 전용 문서 변경을 차단한다. 백엔드를 고치면 전체 테스트 스위트가 돌아 **몇 분 걸린다**(Testcontainers가 Docker로 ES·PG를 띄우기 때문). 정상이다.

사람이 승인한 문서 변경은 `LLMHUB_DOC_EDIT_APPROVED=1 git commit ...`으로 통과시킨다.

---

# 7. 운영 배포 (회사별 설치형)

`docker-compose.yml`이 **운영 기준**이다. 고객에게 나가는 산출물이 이 파일 그대로다(S26/S27, REL-5).

```bash
docker compose -f docker-compose.yml --profile app up -d --build
```

`-f docker-compose.yml`을 **반드시 명시한다.** 저장소에 함께 있는 `docker-compose.override.yml`은 개발용 완화(포트 개방, ES 보안 해제, Keycloak dev 모드)를 담고 있어서, 그냥 `docker compose up`을 하면 조용히 얹힌다.

| | 개발 (`docker compose up`) | 운영 (`-f docker-compose.yml --profile app`) |
|---|---|---|
| 코어·BFF | 소스에서 실행 | 컨테이너 이미지 |
| 노출 포트 | ES·PG·LiteLLM·Keycloak 전부 | **frontend, keycloak 둘뿐** |
| Elasticsearch | 보안 꺼짐 | 보안 켜짐 (`ES_USERNAME`/`ES_PASSWORD`) |
| Keycloak | `start-dev` (내장 H2) | `start` + PostgreSQL |
| API 토큰 직접 발급 | 가능 (password grant) | **불가** — 브라우저 로그인만 |

Keycloak은 브라우저가 로그인 리다이렉트로 직접 접근해야 하므로 노출한다. 코어·Elasticsearch·PostgreSQL·LiteLLM은 내부 네트워크에서만 보인다(SEC-1). 리버스 프록시 뒤에 둔다면 두 포트를 프록시가 흡수하고 compose의 `ports`를 지운다.

## 운영 Keycloak 프로비저닝

realm·역할·클라이언트·audience 매퍼는 `realm-export.json`이 `--import-realm`으로 만든다. 배포별 값(프론트 클라이언트 시크릿·redirect)만 저장소에 커밋하지 않으므로(SEC-3), 스택을 처음 띄운 뒤 한 번 실행한다. 상세 런북은 [docker/keycloak/README.md](docker/keycloak/README.md).

```bash
docker compose -f docker-compose.yml up -d
./docker/keycloak/bootstrap-prod.sh    # 시크릿·redirect 설정 + audience 매퍼 존재 assert
```

`KEYCLOAK_INTERNAL_URL`(기본 `http://keycloak:8080`)은 프론트 컨테이너의 **서버측 백채널**(code→token 교환·토큰 갱신)이 쓰는 내부 주소다. 브라우저 리다이렉트는 외부 `KEYCLOAK_HOSTNAME`으로 가지만, 컨테이너 안에서 외부 URL은 자기 자신이라 도달하지 못한다. dev는 프론트가 호스트에서 돌아 이 값이 외부와 같다.

realm import는 **최초 기동 때만** 적용된다. 이미 데이터가 있는 Keycloak에는 얹히지 않는다.

## 자원 상한

각 서비스에 `*_MEM_LIMIT`(`.env`, 기본 합 ~8GB = 16GB 호스트 가정)로 메모리 상한을 둔다. cgroup 제한이 있어야 JVM(backend·keycloak)이 호스트 전체가 아니라 컨테이너 몫 기준으로 힙을 잡는다. 호스트 사양에 맞게 조정한다. 모든 서비스는 `restart: unless-stopped`다.

## 헬스체크와 기동 순서 (REL-5)

`frontend`는 `backend`와 `keycloak`이 healthy가 된 뒤에 뜬다. 코어가 응답하기 전에 BFF가 올라오면 첫 사용자가 502를 보고, Keycloak이 준비되기 전이면 첫 로그인이 실패한다. Keycloak은 관리 포트(9000)의 `/health/ready`로 healthcheck하는데, 26 이미지엔 curl이 없어 `/bin/sh`(bash)의 `/dev/tcp`로 확인한다.

코어의 헬스 엔드포인트는 **관리 포트(컨테이너 내부 9090)**에 있다. 애플리케이션 포트(8080)에 두면 "모든 API는 인증 필요"(SEC-1)가 깨지기 때문이다. 관리 포트는 호스트에 publish하지 않으므로 컨테이너 밖에서 보이지 않고, 8080의 API 표면은 여전히 전부 인증을 요구한다.

> 포트를 나누는 것만으로는 부족하다. WebFlux의 관리 컨텍스트는 부모의 보안 필터체인을 그대로 쓴다 — 관리 포트도 401을 돌려준다. 그래서 `/actuator/health`를 **관리 포트에 한해** 허용하는 체인을 따로 둔다(`ManagementPortMatcher`). `MANAGEMENT_SERVER_PORT`를 설정하지 않으면 액추에이터는 애플리케이션 포트에 남고, 그때는 인증이 필요하다.

로컬에서 코어를 `bootRun`으로 띄울 때는 관리 포트가 꺼져 있다. 헬스 엔드포인트를 보려면 `MANAGEMENT_SERVER_PORT=9090`을 함께 export한다.

첫 기동 때 `docker/postgres/init/`가 Keycloak용 데이터베이스를 만든다. 데이터 디렉토리가 비어 있을 때만 실행되므로, 기존 볼륨에 얹으려면 `create database keycloak;`을 직접 실행한다.

---

# 8. 장애를 추적할 때 (REL-3)

모든 응답에 `X-Trace-Id` 헤더가 붙는다. 인증에 실패한 401에도 붙는다. 사용자가 신고한 값으로 로그와 감사 기록을 함께 찾는다.

```bash
grep "<trace-id>" backend.log                                    # 색인·검색·LLM 단계
psql -c "select * from audit_log where trace_id = '<trace-id>'"  # 질문·응답 전문
```

클라이언트가 보낸 `X-Trace-Id`는 무시한다 — 추적 ID는 감사 기록의 상관관계 키이므로 요청자가 고를 수 없어야 한다.

로그에는 질문·응답·문서 원문이 남지 않는다(SEC-3). 길이·개수·식별자만 남는다. 전문은 감사 로그가 맡는다(S5, `AUDIT_SCOPE`).

감사 로그는 **대화를 삭제해도 남는다.** 별도 테이블이고 외래키가 없다(S5).
