# llmhub

도메인 중립 RAG 챗 런타임. 회사별 설치형 배포를 전제로, 인증·권한·감사가 내장된 문서 검색 기반 채팅 시스템.

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
ollama pull llama3.1    # 채팅
```

모델을 바꾸면 `.env`의 `EMBEDDING_MODEL`·`EMBEDDING_DIM`과 `config.yaml`을 함께 고친다. 임베딩 모델이 바뀌면 재색인이 필요하고, 차원이 바뀌면 새 ES 인덱스가 필요하다(S8-4, E9).

훅은 커밋 전에 테스트를 돌리고, 테스트 무력화 토큰과 비밀정보, 읽기 전용 문서 변경을 차단한다. 사람이 승인한 문서 변경은 `LLMHUB_DOC_EDIT_APPROVED=1 git commit ...`으로 통과시킨다.

## 요구사항

- Java 21
- Docker + Docker Compose
- Node.js 22
