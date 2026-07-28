# llmhub 인프라 이관 번들 → 옹기종기

llmhub를 세우며 쌓인 **인프라·운영 자산**을 옹기종기로 옮기기 위한 번들이다.
DB 설계 번들(`../db-design-for-onggijonggi/`)의 후속이다.

## 먼저: 이 번들은 "복사본"이 아니다

인프라는 성격이 셋으로 갈리고, **갈래마다 옮기는 방식이 다르다.** 이걸 구분하지 않으면 둘 중 하나가 된다 —
복사할 수 없는 걸 복사하려다 막히거나, 복사하면 되는 걸 손으로 다시 만들거나.

| 갈래 | 폴더 | 어떻게 쓰나 |
|---|---|---|
| ① **자립 파일** | `copy/` | **그대로 복사한다.** 다른 서비스를 참조하지 않아 실제로 드롭인된다 |
| ② **함정 지식** | `compose-checklist.md` | **읽고 대조한다.** 복사할 대상이 애초에 없다(아래 참조) |
| ③ **완성본** | `reference/` | **복사하지 말고 참고만.** 옹기종기에 없는 서비스가 얽혀 있다 |

### 왜 compose를 통째로 주지 않는가

llmhub의 `docker-compose.yml`은 postgres·elasticsearch·keycloak·litellm·backend·frontend **6개 서비스가 서로
엮인** 하나의 완성품이다. 옹기종기는 팀이 서비스를 하나씩 늘려갈 테니, 이걸 그대로 넣으면 존재하지도 않는
backend·frontend를 가리키는 `depends_on`과 환경변수가 딸려온다.

그리고 정작 값진 것은 파일이 아니다. **로그 회전을 안 걸면 며칠 뒤 스택이 통째로 멈춘다는 사실**, **헬스체크의
`localhost`가 IPv6로 풀려 앱이 멀쩡한데도 unhealthy가 된다는 사실** — 이런 건 "아직 쓰지 않은 compose 파일의
속성"이라 복사가 불가능하다. 그래서 ②를 **체크리스트 문서로 새로 썼다.** 이게 이 번들의 핵심 산출물이다.

---

## ① `copy/` — 그대로 복사

| 파일 | 옹기종기에서의 위치 | 언제 필요한가 |
|---|---|---|
| `copy/elasticsearch/Dockerfile` | 예: `docker/elasticsearch/Dockerfile` | ES를 올리는 즉시. 한국어 검색에 필수 |
| `copy/postgres-init/01-keycloak-db.sql` | 예: `docker/postgres/init/01-keycloak-db.sql` | Keycloak을 붙이는 시점 |

- **nori Dockerfile** — `analysis-nori`는 공식 ES 이미지에 **없다.** 직접 구운 이미지를 써야 한국어가 형태소로
  쪼개진다(없으면 "연차휴가는"이 통째로 한 토큰이 되어 "연차휴가"로 검색되지 않는다). compose에서
  `image:` 대신 `build: ./docker/elasticsearch`로 쓴다.
  - **ES 버전을 올릴 때 Dockerfile 태그도 함께 올린다** — 플러그인 버전은 ES 버전에 고정된다.
  - 통합 테스트(Testcontainers)도 **같은 Dockerfile**을 써야 "색인과 검색이 같은 분석기를 쓴다"는 보장이 선다.
  - 폐쇄망 설치는 `artifacts.elastic.co`에서 versioned zip을 미리 받아 오프라인 설치(주석에 있음).
- **postgres init** — 앱 데이터와 인증 데이터를 같은 인스턴스의 **다른 데이터베이스**에 둔다. 데이터 디렉토리가
  비어 있을 때 **한 번만** 실행된다(postgres 이미지 규약).

## ② `compose-checklist.md` — 핵심 산출물

**증상 → 원인 → 처방** 형식의 함정 17건. 5개 묶음(기동·헬스체크 / 포트·네트워크 / 리소스·안정성 /
운영·개발 분리 / 레포 위생). 전부 llmhub에서 **실제로 밟은** 것이고 항목마다 출처를 달았다.

**언제 읽나:** compose를 처음 쓸 때 한 번, 그리고 **증상이 나타났을 때 다시.** 규칙 목록으로 읽으면 안 지켜지고,
증상 목록으로 읽으면 그 상황이 왔을 때 알아본다. 그래서 증상을 앞에 뒀다.

특히 팀 프로젝트라면 **E1(실행 비트가 Windows에서 조용히 유실)** 을 먼저 본다 — git 훅이 POSIX 클론에서
**에러 없이 조용히 꺼지는** 함정이라, 팀원이 늘어나는 순간 제일 먼저 터지는데 알아챌 신호가 없다.

## ③ `reference/` — 복사 금지, 대조용

| 파일 | 원본 |
|---|---|
| `reference/docker-compose.yml` | llmhub 운영 기준 compose (6서비스 완성본) |
| `reference/docker-compose.override.yml` | 개발용 완화 override |
| `reference/env.example` | llmhub `.env.example` (파일명의 선행 점만 제거) |

옹기종기에서 llmhub 레포를 열어볼 수 없으니 동봉했다. **서비스를 하나 추가할 때 "llmhub은 이걸 어떻게 썼나"를
대조하는 용도**다. 통째로 복사하면 위에 적은 이유로 깨진다.

- 세 파일 모두 **주석이 본문만큼 값지다.** 왜 그 값인지가 적혀 있다(포트를 5433으로 둔 이유, 메모리 상한의
  근거, 임베딩 모델명이 데이터 계약인 이유 등).
- `env.example`은 **전부 placeholder(`change-me`)** 다. 실제 비밀값은 들어 있지 않다.
- 파일명에서 선행 점을 뺀 이유: `.env*` 패턴을 쓰는 gitignore에 걸려 조용히 누락되는 사고를 막기 위해서다.

---

## 안 담은 것

- **JPA 엔티티·`application.yml`** — 이관 대상이 아니라고 결론냈다. 스키마(Flyway)가 원본이고 엔티티는 파생물이라
  TDD로 팀이 짜면 떨어져 나오며, 타 패키지 타입 의존이 딸려와 깔끔히 분리되지도 않는다.
- **Keycloak realm/부트스트랩·LiteLLM 설정** — 옹기종기의 인증·모델 구성이 정해진 뒤에 따로 다룬다.

## 경고

- 이 번들은 **llmhub의 특정 시점 스냅샷**이다. 이후 llmhub이 바뀌어도 **자동 동기화되지 않는다**(일회성 이관).
- **비밀정보 없음** — placeholder와 설계·운영 지식뿐이다. 키·토큰·실제 비밀번호는 담기지 않았다.
- 버전 전제: PostgreSQL 17 · Elasticsearch 9.4.3(+nori) · Keycloak 26. 옹기종기가 다른 버전을 쓰면
  체크리스트의 **증상은 대체로 유효하지만 처방의 세부(이미지에 curl이 있는지 등)는 다시 확인**해야 한다.
