# Keycloak 프로비저닝 — dev / 운영

llmhub는 Keycloak realm을 **선언적(import) + 배포별(script)** 두 겹으로 만든다.
비밀·호스트별 값은 저장소에 커밋하지 않는다(SEC-3).

## realm-export.json 이 만드는 것 (dev·운영 공통, 선언적)

`docker-compose.yml`의 Keycloak은 `start --import-realm`(dev override는 `start-dev --import-realm`)로
`realm-export.json`을 읽는다. 여기서 오는 것:

- realm `llmhub` + 역할 `USER` / `ADMIN`
- `llmhub-backend` — 리소스 서버(bearer-only). JWT 검증만 한다.
- `llmhub-frontend` — 프론트 BFF(next-auth) Authorization Code Flow 클라이언트 **구조**
  (`standardFlowEnabled=true`, `directAccessGrantsEnabled=false`, redirect/webOrigins 비어 있음, 시크릿 없음)
- `llmhub-frontend`의 **audience 매퍼** `llmhub-backend-audience` → access token에 `aud=llmhub-backend`

> **매퍼가 여기 선언적으로 있는 이유:** 백엔드가 `aud=llmhub-backend`를 강제한다(R-16). 매퍼가 없으면
> 정당한 로그인도 백엔드가 401로 막는다(fail-closed). import에 넣어 **모든 환경에서 잊힐 수 없게** 한다.

Keycloak은 realm import를 **최초 기동 때 한 번만** 적용한다. 이미 realm이 있으면 무시한다.

## 배포별 값 — bootstrap 스크립트가 얹는다

realm-export.json에 넣을 수 없는 것(시크릿·호스트별 redirect)만 스크립트가 설정한다.

### dev — `bootstrap-dev.sh`

```bash
docker compose up -d           # override가 자동으로 얹힌다
./docker/keycloak/bootstrap-dev.sh
```

얹는 것:
- 프론트 클라이언트 시크릿(`KEYCLOAK_CLIENT_SECRET`) · redirect/origin(`FRONTEND_ORIGIN`)
- `directAccessGrantsEnabled=true` — **dev 전용**. 스모크 테스트가 password grant로 토큰을 받는다.
- dev 사용자 `dev-user`(USER) · `dev-admin`(ADMIN) + 비밀번호

### 운영 — `bootstrap-prod.sh`

```bash
docker compose -f docker-compose.yml up -d     # 운영 파일만 (override 제외)
./docker/keycloak/bootstrap-prod.sh
```

얹는 것:
- 프론트 클라이언트 시크릿(`KEYCLOAK_CLIENT_SECRET`) · redirect/origin(`AUTH_URL`, 없으면 `FRONTEND_ORIGIN`)
- `directAccessGrantsEnabled=false`로 굳힘(운영 하드닝)
- 끝에 **audience 매퍼 존재를 assert** — 없으면 크게 실패한다(import drift 방지)

얹지 **않는** 것: 실 사용자(회사 IdP 연동 또는 관리자 몫), dev 사용자, direct access grant.

## 필요한 .env 변수

| 변수 | dev | 운영 | 용도 |
|---|---|---|---|
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | ✓ | ✓ | kcadm 로그인(부트스트랩 관리자) |
| `KEYCLOAK_CLIENT_ID` (기본 `llmhub-frontend`) | ✓ | ✓ | 프론트 클라이언트 id |
| `KEYCLOAK_CLIENT_SECRET` | ✓ | ✓ | 프론트 클라이언트 시크릿(커밋 금지) |
| `FRONTEND_ORIGIN` | ✓ | fallback | dev redirect/origin |
| `AUTH_URL` | — | ✓ | 운영 redirect/origin(프론트 공개 주소) |
| `DEV_USER_PASSWORD` / `DEV_ADMIN_PASSWORD` | ✓ | — | dev 사용자 비밀번호 |

## 주의

- Keycloak 컨테이너를 **재생성**(볼륨 초기화)하면 realm이 다시 import되고, 배포별 값은 사라진다 →
  해당 환경의 bootstrap 스크립트를 **다시 돌린다.**
- 운영에서 `-f docker-compose.yml`을 반드시 명시한다. override가 얹히면 dev 완화(ES 보안 끔, KC dev 모드)가
  섞인다. `bootstrap-prod.sh`도 같은 이유로 `-f docker-compose.yml`로 컨테이너를 지정한다.
