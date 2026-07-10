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
| [docs/LEARNINGS.md](docs/LEARNINGS.md) | 누적된 교훈 — 세션 시작 시 읽는다 |
| [docs/requirements/](docs/requirements/) | 모듈별 상세 요구사항 (= TDD 단위) |

## 로컬 개발 환경

```bash
cp .env.example .env    # 값을 채운다. .env는 커밋하지 않는다.
docker compose up -d    # PostgreSQL · Elasticsearch(nori) · Keycloak · LiteLLM
```

## 요구사항

- Java 21
- Docker + Docker Compose
- Node.js 22 (프론트엔드는 CHAT 모듈 착수 시 추가된다)
