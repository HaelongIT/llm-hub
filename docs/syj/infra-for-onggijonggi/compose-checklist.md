# docker-compose 함정 체크리스트

> llmhub를 세우며 **실제로 밟은** 함정들이다. 옹기종기에서 compose를 쓸 때 이 목록과 대조한다.
> 전부 `docs/LEARNINGS.md`(llmhub) 또는 compose·`.env.example` 주석에 근거가 있다 — 각 항목 끝에 출처를 적었다.
> 추측으로 넣은 항목은 없다.

**읽는 법:** 규칙만 외우면 안 지켜진다. 그래서 **증상부터** 적었다. 그 증상이 왔을 때 이 문서를 다시 펴라.

---

## A. 기동 · 헬스체크

### A1. 앱은 멀쩡한데 헬스체크만 실패한다
- **증상:** 컨테이너가 `unhealthy`로 떨어지는데 라우트는 정상. 로그엔 `Connection refused`.
- **원인:** Alpine에서 `localhost`가 **`::1`(IPv6)로 먼저** 풀리는데, 서버(Next standalone 등)는 `0.0.0.0` 즉 **IPv4에만** 바인딩한다.
- **처방:** 헬스체크 URL을 `127.0.0.1`로 고정한다.
- **덤 함정:** 상태를 `docker ps | grep -c healthy`로 세면 **`unhealthy`도 매치된다.** 이 오탐 때문에 처음엔 통과한 줄 알았다. 상태 문자열은 정확히 비교할 것.
- *출처: LEARNINGS 2026-07-10 OPS「컨테이너 헬스체크의 localhost는 ::1로 풀린다」*

### A2. Keycloak 헬스체크를 짤 수단이 없다
- **증상:** KC 컨테이너에 `curl`도 `wget`도 없어서 헬스체크를 못 만든다.
- **원인:** Keycloak 26 이미지엔 둘 다 없다. 다만 `/bin/sh`가 bash라 `/dev/tcp`를 쓸 수 있다.
- **처방:** `KC_HEALTH_ENABLED=true`로 두면 **관리 포트 9000**에 `/health/ready`가 뜬다(dev `start-dev`에서도 동작 — health는 모드 무관). `exec 3<>/dev/tcp/localhost/9000` + `printf 'GET /health/ready HTTP/1.1\r\n…'` + `grep -q '200 OK'`. 실측 재생성 후 ~25초에 healthy.
- **주의:** healthcheck는 **컨테이너 생성 시점에 박힌다.** 고쳐도 적용하려면 `up -d --force-recreate <svc>`가 필요하다.
- *출처: LEARNINGS 2026-07-12「자잘한 하드닝 마감 — KC healthcheck(L-9)」*

### A3. node:alpine에도 curl이 없다
- **처방:** `wget`을 쓴다(BusyBox 내장). 이미지마다 있는 도구가 다르다 — 헬스체크를 쓰기 전에 그 이미지에 실제로 있는지 확인한다.
- *출처: llmhub `docker-compose.yml` frontend healthcheck 주석*

### A4. 스택은 다 떴는데 "첫 로그인만" 실패한다
- **증상:** 기동 직후 첫 사용자만 로그인 실패. 잠시 뒤 재시도하면 된다.
- **원인:** `depends_on`이 `service_started`면 Keycloak이 **준비되기 전 창**에 프론트가 떠 버린다.
- **처방:** `depends_on: keycloak: condition: service_healthy`. (그러려면 A2의 헬스체크가 먼저 있어야 한다.)
- *출처: LEARNINGS 2026-07-12 (L-9), llmhub `docker-compose.yml` frontend `depends_on` 주석*

### A5. 최초 기동에서만 backend가 즉사한다
- **증상:** 완전히 새 볼륨으로 처음 띄울 때 backend가 PostgreSQL에 `Connection refused`로 죽는다. 두 번째부터는 정상.
- **원인:** postgres가 init 스크립트(`docker-entrypoint-initdb.d`)를 도는 동안 **소켓으로는 `pg_isready`를 통과**시켜 compose는 healthy로 판단하는데, TCP는 아직 안 열려 있다.
- **처방:** init이 끝난 뒤 `up -d`를 한 번 더 돌린다. 또는 **`restart: unless-stopped`를 두면 자동 복구**된다(llmhub은 이 정책이 없어서 겪었다).
- *출처: LEARNINGS 2026-07-12「브라우저 E2E 검증 — postgres init 레이스 + restart 정책 부재」*

### A6. Elasticsearch 인증이 간헐적으로 실패한다
- **증상:** `unable to authenticate user [elastic]`가 가끔 나고, 재실행하면 통과한다.
- **원인:** 보안이 켜진 ES는 **HTTP 계층이 먼저 401을 돌려주기 시작**하고, `elastic` 사용자를 담는 네이티브 렘은 **그 뒤에** 초기화된다. 그 틈에 인증하면 실패한다. 401을 "떴다"의 신호로 쓴 것이 원인.
- **처방:** 대기 조건을 "응답한다"가 아니라 **"자격증명이 실제로 통한다"**(basic auth + 200)로 바꾼다.
- **일반화:** 컨테이너 대기 조건은 **내가 실제로 의존하는 상태**여야 한다. 포트가 열렸다·응답이 왔다는 준비 완료가 아니다.
- *출처: LEARNINGS 2026-07-10 TEST「Testcontainers ES: "HTTP가 응답한다"와 "elastic 사용자가 준비됐다"는 다르다」*

### A7. 헬스 엔드포인트가 401을 준다 (관리 포트를 나눴는데도)
- **증상:** "모든 API는 인증 필요"를 지키면서 오케스트레이터가 자격증명 없이 기동을 확인해야 하는데, 관리 포트로 분리해도 헬스가 401.
- **원인:** **WebFlux에서 관리 자식 컨텍스트는 부모의 `WebFilter`(`WebFilterChainProxy`)를 그대로 쓴다.** 서블릿 스택의 `ManagementWebSecurityAutoConfiguration` 동작을 리액티브에 그대로 적용하면 틀린다. 실측: 관리 포트 응답에 `WWW-Authenticate: Bearer`가 그대로 있었다.
- **처방:** 최우선 순위 보안 체인을 하나 더 두고, `securityMatcher`를 **"관리 포트 AND `/actuator/health`"** 로 좁혀 `permitAll`. 경로만으로 열면 **애플리케이션 포트의 헬스까지 열려** 보안 요구가 깨진다.
- **일반화:** 보안 경계에 대한 프레임워크 동작은 **문서가 아니라 응답으로** 확인한다.
- *출처: LEARNINGS 2026-07-10 OPS「관리 포트를 분리해도 보안 필터체인은 따라온다」*

---

## B. 포트 · 네트워크

### B1. "DB는 멀쩡한데 앱만 못 붙는다" — 사실은 포트 선점
- **증상:** 앱이 `password authentication failed for user "..."`로 죽는다. 컨테이너에 들어가 보면 DB는 정상.
- **원인:** 호스트에 PostgreSQL이 설치돼 있으면 그쪽이 `0.0.0.0:5432`를 **먼저** 잡는다. Docker는 `[::]:5432`(IPv6)만 잡는다. 그래서 호스트에서 나가는 연결이 **컨테이너가 아니라 로컬 서버로** 간다. 거기엔 그 비밀번호의 사용자가 없다.
- **처방:** 개발용 호스트 포트를 **5433**처럼 비켜 쓴다(compose 매핑과 datasource URL이 같은 변수를 쓰면 한 곳만 고치면 된다).
- **★ 오진 함정:** `docker compose exec postgres psql -U <user>`는 **잘 된다.** 컨테이너 내부는 **trust 인증이라 틀린 비밀번호로도 통과**한다. 이걸로 비밀번호를 검증하면 안 된다 — "DB는 되는데 앱만 안 된다"로 보여 비밀번호·CRLF·환경변수를 먼저 의심하게 만든다.
- **진단:** 5432 리스너가 **둘**인지 본다(`netstat -ano | grep :5432`).
- *출처: LEARNINGS 2026-07-10 INFRA「로컬 PostgreSQL이 5432를 가로챈다」, `.env.example` POSTGRES_PORT 주석*

### B2. 포트를 최소로 닫았더니 로그인이 아예 안 된다
- **증상:** 보안을 위해 내부망 전용으로 조였는데 로그인 자체가 불가능해진다.
- **원인:** **브라우저가 OIDC 리다이렉트로 Keycloak에 직접 접근해야 한다.** IdP는 "내부 서비스"가 아니다. (llmhub의 실제 설계 버그였다 — 문서의 내부망 목록에 Keycloak이 없었는데 그걸 놓쳤다.)
- **처방:** 외부 노출은 **사용자 진입점(프론트) + IdP(Keycloak)** 둘만. 코어·ES·PostgreSQL·LLM 게이트웨이는 내부 네트워크 전용. 리버스 프록시를 둔다면 그 둘을 프록시가 흡수하고 compose에서 publish를 지운다.
- *출처: LEARNINGS 2026-07-10 INFRA「운영 compose는 base, 개발은 override」발견 1, llmhub `docker-compose.yml` 머리말*

---

## C. 리소스 · 안정성

### C1. 며칠 잘 돌던 스택이 통째로 멈춘다
- **증상:** 특별한 배포도 없었는데 어느 날 전체 정지.
- **원인:** Docker 기본 `json-file` 로그 드라이버는 **크기 제한이 없다.** 로그가 무한히 쌓여 호스트 디스크를 소진 → PostgreSQL WAL 실패, Elasticsearch read-only 전환 → 스택 전체 정지.
- **처방:** **모든** 서비스에 로그 회전을 건다. YAML 앵커로 한 번만 정의하면 된다:
  ```yaml
  x-logging: &default-logging
    driver: json-file
    options: { max-size: "10m", max-file: "5" }
  ```
  각 서비스에 `logging: *default-logging`. 하나라도 빠지면 그 서비스가 디스크를 채운다.
- *출처: LEARNINGS 2026-07-11「배포 설정 위생」R-7*

### C2. 단일 호스트에서 JVM들이 OOM-kill된다
- **증상:** 여러 JVM 서비스(백엔드·Keycloak 등)를 한 호스트에 올리면 물리 메모리를 초과해 죽는다.
- **원인:** cgroup 제한이 없으면 JVM의 `MaxRAMPercentage`(예: 75)가 **컨테이너 몫이 아니라 호스트 전체 RAM** 기준으로 힙 상한을 잡는다. 서비스마다 각자 호스트의 75%를 노린다.
- **처방:** 서비스마다 `mem_limit`을 준다. 그러면 JVM이 그 몫 기준으로 계산한다. llmhub 기본값 합계 ~8GB는 **16GB급 단일 호스트** 가정이다 — 호스트 사양에 맞게 조정할 것.
- *출처: `.env.example` 「서비스 메모리 상한」주석, llmhub `docker-compose.yml` backend `mem_limit` 주석*

### C3. 일시적 실패에서 자동 복구가 안 된다
- **처방:** `restart: unless-stopped`. A5(init 레이스)처럼 "잠시 뒤면 되는" 실패가 영구 실패로 굳는 걸 막는다.
- *출처: LEARNINGS 2026-07-12 (B3 리뷰 항목)*

---

## D. 운영 / 개발 분리

### D1. 운영인데 보안이 꺼져 있다
- **증상:** 운영 배포인데 ES 보안이 off, Keycloak이 dev 모드, 포트가 다 열려 있다.
- **원인:** 저장소에 `docker-compose.override.yml`이 있으면 **`docker compose up`이 그것을 조용히 얹는다.** 개발 완화가 운영에 그대로 적용된다.
- **처방:** `docker-compose.yml`은 **운영 기준**으로 두고(고객에게 나가는 산출물이 이것 그대로), override는 개발 완화 전용. 운영에서는 **`docker compose -f docker-compose.yml up -d`로 명시**한다. 개발용 앱 컨테이너는 `profiles: ["app"]`로 묶어 기본 `up`에서 뺀다.
- *출처: LEARNINGS 2026-07-10 INFRA「운영 compose는 base, 개발은 override」*

### D2. Keycloak 운영 모드는 실제 DB를 요구한다
- **원인:** `start`(운영 모드)에서 내장 H2는 **지원되지 않는다.** dev 전용이다.
- **처방:** 같은 PostgreSQL 인스턴스에 `keycloak` 데이터베이스를 따로 만들어 붙인다(앱 스키마와 안 섞이고, 나중에 분리할 때 옮기기만 하면 된다). → `copy/postgres-init/01-keycloak-db.sql`
- **주의:** `docker-entrypoint-initdb.d`의 스크립트는 **데이터 디렉토리가 비어 있을 때 한 번만** 실행된다. 이미 데이터가 있으면 안 돈다.
- *출처: LEARNINGS 2026-07-10 INFRA「운영 compose는 base…」발견 2*

### D3. ★ 운영자가 설정을 바꿔도 아무 일도 안 일어난다
- **증상:** `.env`에서 분석기를 nori→english로 바꾸고, 업로드 상한을 올려도 **동작이 그대로**다.
- **원인:** 개발은 앱을 `bootRun`으로 돌려 `.env`를 직접 읽지만, **컨테이너는 compose `environment:`에 나열된 것만 본다.** llmhub에서 실제로 **8개+ 튜너블이 전달되지 않고 있었다**(분석기·인덱스명·청크크기·업로드상한·컨텍스트상한·질문상한·BM25/벡터 가중치 + 타임아웃 5개). 즉 "설정 외부화"가 **개발에서만 참**이었다.
- **처방:** 앱이 읽는 env var 목록과 compose `environment:`를 **대조**해, 전부 `KEY: ${KEY:-기본값}`(기본값은 앱 설정과 동일)으로 배선한다.
- **검증법:** `docker compose --env-file .env.example -f docker-compose.yml config`가 앵커·변수를 해석해 최종 서비스 정의를 뱉는다. 여기서 키가 있는지 본다. **더 확실한 건 `docker exec <c> env | grep KEY`** — 컨테이너 안에 진짜 있는지 보는 것.
- *출처: LEARNINGS 2026-07-11「배포 설정 위생」R-6, 2026-07-13「게이트웨이 뒷단을 원격으로」R-6 재확인*

### D4. 선언만 있고 죽어 있는 설정
- **증상 1:** 앱 설정에 값을 **리터럴로 박아** 두면 `.env`의 같은 이름 변수가 무시된다. → `${KEY:기본값}` 형태로.
- **증상 2:** `.env.example`엔 있는데 **읽는 코드가 없는** 변수. llmhub의 `SEARCH_ANALYZER`가 실제로 그랬다 — 코드는 `"nori"`를 하드코딩하고 있었고, 클래스 주석은 "전부 설정값이며 하드코딩하지 않는다"라고 **주장**하고 있었다. 주석이 코드보다 앞서 있었다.
- **처방:** `.env.example`의 변수와 앱이 실제로 읽는 키를 **주기적으로 대조**한다. 선언만 있고 읽지 않는 설정은 문서가 거짓말을 하게 만든다.
- **검증은 "설정을 넣었다"가 아니다:** llmhub은 분석기를 `standard`로 바꿔 **실제 ES 매핑을 읽어** 확인했다. 설정이 결과물에 닿지 않으면 외부화한 것이 아니다.
- *출처: LEARNINGS 2026-07-10 OPS「문서가 이름까지 댄 설정이 죽어 있었다」, 2026-07-11 R-15*

---

## E. 레포 위생 (팀 협업에서 바로 터진다)

### E1. ★ Windows에서 만든 레포를 mac/Linux에서 클론하면 스크립트가 안 돈다
- **증상:** POSIX에서 `./gradlew` → `permission denied`. **Windows에서는 아무 증상이 없다.**
- **원인:** Windows는 `core.filemode=false`라 git이 **실행 비트를 추적하지 않는다.** 최초 커밋 때 스크립트가 인덱스에 `100644`로 굳고, Windows엔 파일 모드 개념이 없어 드러나지 않는다. llmhub에선 shebang 파일 4개 중 2개가 644였다 — **어느 게 정상인지는 우연**이었다.
- **처방:** `chmod +x`는 `filemode=false` 아래에서 **git이 무시한다**(워킹트리만 바뀜). 인덱스 모드를 직접 쓰는 **`git update-index --chmod=+x <path>`** 만 통한다.
- **★ 진짜 위험한 건 gradlew가 아니라 git 훅이다:** POSIX에서 git은 **실행 비트 없는 훅을 에러 없이 건너뛴다.** `core.hooksPath`를 설정해도 **커밋은 성공하고 훅만 안 돈다** → 비밀정보 차단·테스트 강제·문서 잠금이 **조용히 전부 꺼진다.** gradlew는 에러라도 나서 사람이 알아채지만, 이건 알아챌 신호가 없다. **팀원이 늘어나는 순간 이게 제일 먼저 터진다.**
- **줄바꿈도 같이:** `core.autocrlf=true` + 루트 `.gitattributes` 부재 = 워킹트리 CRLF. POSIX에 새어 나가면 `bad interpreter: ^M`. `*.sh text eol=lf`와 훅 경로를 고정한다. blob이 이미 LF면 **diff가 안 생기고 재정규화가 no-op**이라 부담 없이 넣을 수 있다. (줄바꿈과 실행 비트는 별개 문제다 — `.gitattributes`로 실행 비트는 설정할 수 없다.)
- **Dockerfile은 체크아웃 모드를 상속한다:** `COPY gradlew ./`가 644를 그대로 옮겨 `RUN ./gradlew`가 죽는다. `RUN chmod +x gradlew` 한 줄로 빌드를 체크아웃 모드에서 독립시킨다.
- *출처: LEARNINGS 2026-07-13「리포 이식성 — 실행 비트가 Windows에서 조용히 유실된다」*

### E2. 데이터 볼륨이 레포 안에 있으면 언젠가 커밋된다
- **증상:** 테스트가 만든 업로드 원본이 추적되고 있다.
- **원인:** `.gitignore`의 **선행 슬래시**(`/data/`)는 **저장소 루트만** 잡는다. 테스트가 하위 디렉토리(`backend/`)에서 돌면 `backend/data/`는 안 걸린다. llmhub에선 이렇게 파일 27개가 여러 커밋 전부터 추적되고 있었다.
- **처방:** `data/`로 쓴다(어느 깊이에서든 매치). 그리고 `git add -A <디렉토리>`는 무시되지 않는 산출물을 **조용히 삼킨다.**
- **일반화:** `.gitignore` 패턴은 **작업 디렉토리가 바뀌는 경우**를 상상해서 쓴다.
- *출처: LEARNINGS 2026-07-10 SEC「.gitignore의 선행 슬래시가 업로드 원본을 놓쳤다」*

---

## 마지막 — compose는 유닛 테스트가 없다

그래서 **resolve와 실측이 검증을 대신한다.** 최소한 이 둘은 습관으로 둘 것:

1. `docker compose --env-file <env> -f docker-compose.yml config` — 앵커·변수가 해석된 **최종** 정의를 본다.
2. `docker exec <container> env | grep <KEY>` — 컨테이너 안에 설정이 **진짜로** 있는지 본다.

그리고 헬스체크는 **반드시 실제로 통과하는 것을 눈으로 본다.** `depends_on: service_healthy`는 헬스체크가 틀리면 조용히 기동을 막거나 영영 unhealthy로 남는다.
