# llmhub

도메인 중립 RAG 챗 런타임. 회사별 설치형 배포를 전제로, 인증·권한·감사가 내장된 문서 검색 기반 채팅 시스템.

> **상태: v0 완료.** 색인→로그인→질문→근거 검색→스트리밍 응답→감사가 end-to-end로 동작한다. 코드리뷰 2회 + 실검증(뮤테이션 테스트·살아 있는 스택·Playwright E2E)을 거쳤다.
>
> **프로젝트를 처음 본다면 → [docs/syj/onboarding.md](docs/syj/onboarding.md)** (무엇이고 왜 이렇게 만들었는지 설명하는 팀 온보딩 문서).

## 문서

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

## 로컬 개발 환경

```bash
git config core.hooksPath .githooks   # 필수. 테스트·비밀정보·문서 보호 훅을 켠다.
cp .env.example .env                  # 값을 채운다. .env는 커밋하지 않는다.
docker compose up -d                  # PostgreSQL · Elasticsearch(nori) · Keycloak · LiteLLM
```

인프라가 뜨면 Keycloak에 개발용 계정과 프론트엔드 클라이언트를 만든다. 비밀번호와 클라이언트 시크릿은 저장소에 커밋하지 않으므로(SEC-3) `realm-export.json`이 아니라 이 스크립트가 `.env` 값으로 만든다. 재실행해도 안전하다.

```bash
./docker/keycloak/bootstrap-dev.sh
```

| 계정 | 역할 | 접근 태그 |
|---|---|---|
| `dev-user` | USER | `{public}` |
| `dev-admin` | ADMIN | `{public, restricted}` + 색인 API |

그다음 코어와 BFF를 각각 실행한다.

```bash
set -a; . ./.env; set +a                          # 설정을 환경변수로
cd backend && ./gradlew --no-daemon bootRun       # 코어 (WebFlux, :8080)
cd frontend && npm install && npm run dev         # BFF + UI (:3000)
```

> `--no-daemon`이 필요하다. 살아 있는 Gradle 데몬은 나중에 export한 환경변수를 물려받지 못해, 설정이 조용히 기본값으로 떨어진다.

클라이언트는 BFF만 호출한다. 코어·Elasticsearch·PostgreSQL·LiteLLM은 내부망 전용이다(SEC-1).

### 색인·질문을 실제로 돌리려면

기본 `docker/litellm/config.yaml`은 호스트의 Ollama를 바라본다. 모델을 먼저 받아둔다.

```bash
ollama pull bge-m3      # 임베딩 (1024차원 — .env의 EMBEDDING_DIM과 일치해야 한다)
ollama pull qwen3:8b    # 채팅 (.env의 LITELLM_CHAT_BACKEND 기본값)
```

모델을 바꾸면 `.env`의 `EMBEDDING_MODEL`·`EMBEDDING_DIM`과 `config.yaml`을 함께 고친다. 임베딩 모델이 바뀌면 재색인이 필요하고, 차원이 바뀌면 새 ES 인덱스가 필요하다(S8-4, E9).

## 운영 배포 (회사별 설치형)

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

Keycloak은 브라우저가 로그인 리다이렉트로 직접 접근해야 하므로 노출한다. 코어·Elasticsearch·PostgreSQL·LiteLLM은 내부 네트워크에서만 보인다(SEC-1). 리버스 프록시 뒤에 둔다면 두 포트를 프록시가 흡수하고 compose의 `ports`를 지운다.

### 운영 Keycloak 프로비저닝

realm·역할·클라이언트·audience 매퍼는 `realm-export.json`이 `--import-realm`으로 만든다. 배포별 값(프론트 클라이언트 시크릿·redirect)만 저장소에 커밋하지 않으므로(SEC-3), 스택을 처음 띄운 뒤 한 번 실행한다. 상세 런북은 [docker/keycloak/README.md](docker/keycloak/README.md).

```bash
docker compose -f docker-compose.yml up -d
./docker/keycloak/bootstrap-prod.sh    # 시크릿·redirect 설정 + audience 매퍼 존재 assert
```

`KEYCLOAK_INTERNAL_URL`(기본 `http://keycloak:8080`)은 프론트 컨테이너의 **서버측 백채널**(code→token 교환·토큰 갱신)이 쓰는 내부 주소다. 브라우저 리다이렉트는 외부 `KEYCLOAK_HOSTNAME`으로 가지만, 컨테이너 안에서 외부 URL은 자기 자신이라 도달하지 못한다. dev는 프론트가 호스트에서 돌아 이 값이 외부와 같다.

### 자원 상한

각 서비스에 `*_MEM_LIMIT`(`.env`, 기본 합 ~8GB = 16GB 호스트 가정)로 메모리 상한을 둔다. cgroup 제한이 있어야 JVM(backend·keycloak)이 호스트 전체가 아니라 컨테이너 몫 기준으로 힙을 잡는다. 호스트 사양에 맞게 조정한다. 모든 서비스는 `restart: unless-stopped`다.

### 헬스체크와 기동 순서 (REL-5)

`frontend`는 `backend`와 `keycloak`이 healthy가 된 뒤에 뜬다. 코어가 응답하기 전에 BFF가 올라오면 첫 사용자가 502를 보고, Keycloak이 준비되기 전이면 첫 로그인이 실패한다. Keycloak은 관리 포트(9000)의 `/health/ready`로 healthcheck하는데, 26 이미지엔 curl이 없어 `/bin/sh`(bash)의 `/dev/tcp`로 확인한다.

코어의 헬스 엔드포인트는 **관리 포트(컨테이너 내부 9090)**에 있다. 애플리케이션 포트(8080)에 두면 "모든 API는 인증 필요"(SEC-1)가 깨지기 때문이다. 관리 포트는 호스트에 publish하지 않으므로 컨테이너 밖에서 보이지 않고, 8080의 API 표면은 여전히 전부 인증을 요구한다.

> 포트를 나누는 것만으로는 부족하다. WebFlux의 관리 컨텍스트는 부모의 보안 필터체인을 그대로 쓴다 — 관리 포트도 401을 돌려준다. 그래서 `/actuator/health`를 **관리 포트에 한해** 허용하는 체인을 따로 둔다(`ManagementPortMatcher`). `MANAGEMENT_SERVER_PORT`를 설정하지 않으면 액추에이터는 애플리케이션 포트에 남고, 그때는 인증이 필요하다.

로컬에서 코어를 `bootRun`으로 띄울 때는 관리 포트가 꺼져 있다. 헬스 엔드포인트를 보려면 `MANAGEMENT_SERVER_PORT=9090`을 함께 export한다.

첫 기동 때 `docker/postgres/init/`가 Keycloak용 데이터베이스를 만든다. 데이터 디렉토리가 비어 있을 때만 실행되므로, 기존 볼륨에 얹으려면 `create database keycloak;`을 직접 실행한다.

훅은 커밋 전에 테스트를 돌리고, 테스트 무력화 토큰과 비밀정보, 읽기 전용 문서 변경을 차단한다. 사람이 승인한 문서 변경은 `LLMHUB_DOC_EDIT_APPROVED=1 git commit ...`으로 통과시킨다.

## 장애를 추적할 때 (REL-3)

모든 응답에 `X-Trace-Id` 헤더가 붙는다. 인증에 실패한 401에도 붙는다. 사용자가 신고한 값으로 로그와 감사 기록을 함께 찾는다.

```bash
grep "<trace-id>" backend.log                                    # 색인·검색·LLM 단계
psql -c "select * from audit_log where trace_id = '<trace-id>'"  # 질문·응답 전문
```

클라이언트가 보낸 `X-Trace-Id`는 무시한다 — 추적 ID는 감사 기록의 상관관계 키이므로 요청자가 고를 수 없어야 한다.

로그에는 질문·응답·문서 원문이 남지 않는다(SEC-3). 길이·개수·식별자만 남는다. 전문은 감사 로그가 맡는다(S5, `AUDIT_SCOPE`).

## 요구사항

- Java 21
- Docker + Docker Compose
- Node.js 22
