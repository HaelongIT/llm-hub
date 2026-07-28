# llmhub은 Keycloak 인증 사용자 → app_user 프로비저닝을 어떻게 했나

> 옹기종기 팀에서 올라온 **[진짜 논의 필요]** 항목 — "Keycloak 인증 사용자를 언제·어떻게 Postgres
> `app_user`에 등록하나" — 에 대해, 같은 문제를 먼저 겪은 llmhub이 실제로 어떻게 구현했는지 공유하는
> 문서다. 팀 초안(JWT sub로 조회 → 없으면 upsert, lazy/JIT)이 **방향이 맞다**는 것과, 그걸 그대로 짜면
> 밟는 함정 하나를 담았다.

## 한 줄 결론

**팀 초안 = llmhub이 실제로 쓰는 방식.** 별도 동기화 배치 없이, JWT `sub`로 조회하고 없으면 그 자리에서
만든다(lazy/JIT). 다만 llmhub은 이걸 구현하다 **경합 버그**를 밟았고(부하 테스트가 잡음), 그 처방까지
포함해서 가져가면 팀은 같은 버그를 안 밟는다.

---

## 1. 어디에 두나 — "채팅 것"이 아니라 공용 컴포넌트

팀 논의는 이걸 "CORE 3번(세션 이력)에 넣을까, app_user 소유나 인증 영역이 맡을까"로 봤다. llmhub의 답은
**셋 다 아니다 — `common/user` 공용 컴포넌트**다.

```
common/user/
├─ AppUserRepository.java          ← 인터페이스 (계약)
├─ PostgresAppUserRepository.java  ← 구현: on-conflict upsert
├─ AppUserJpaRepository.java       ← insertIfAbsent 네이티브 쿼리
└─ AppUserEntity.java              ← id, keycloak_subject, created_at 뿐
```

인터페이스는 메서드 하나다:

```java
/** Keycloak subject로 사용자를 찾거나 만든다. 역할은 저장하지 않는다 — 이중 관리가 되기 때문이다. */
UUID ensureExists(String keycloakSubject);
```

**왜 채팅 것이 아닌가:** user_id가 필요한 **모든 인증 진입점**이 각자 이걸 부른다. 채팅만이 아니다.

| 부르는 곳 | 언제 |
|---|---|
| `SessionController` | 세션 생성·목록·조회·삭제 — CRUD 전부 |
| `ChatController` | 채팅 스트림 시작할 때 |
| `IndexController` | **문서 업로드** — "누가 올렸나"를 document에 남길 때 |

전부 `userRepository.ensureExists(jwt.getSubject())` 한 줄. 그래서 초안이 트리거를 "채팅 첫 요청"으로
좁힌 건 실제보다 좁다 — **인증된 사용자가 DB에 기록을 남기는 첫 지점이면 어디든** 일어나야 한다.
인터페이스 하나로 계약을 고정했기 때문에 누가 구현하든 세 소비자는 안 바뀐다.

## 2. 함정 — 초안의 "조회 → 없으면 insert"는 경합한다 (llmhub이 밟음)

가장 자연스러운 구현:

```java
// ⚠ 이렇게 짜면 버그가 있다
var found = repo.findByKeycloakSubject(subject);
if (found.isEmpty()) repo.insert(subject);   // ← 여기가 경합한다
```

**증상:** 신규 사용자의 첫 요청이 `keycloak_subject` unique 제약 위반으로 간헐 실패.
**원인:** 같은 신규 사용자의 첫 요청이 **동시에 여러 개** 들어오면(브라우저가 로그인 직후 세션 목록 +
첫 메시지를 병렬로 친다) 전부 "없다"고 판단하고 전부 insert → 하나 빼고 unique 위반.
**어떻게 발견했나:** 부하 테스트가 잡았다. 단일 요청 테스트로는 안 나온다.

**처방:** insert를 `on conflict do nothing`으로 **원자화**하고, 그 뒤에 읽는다. 앱 레벨 락을 잡지 않고
DB의 unique 제약을 그대로 쓴다. 경합에서 진 트랜잭션은 아무것도 안 하고, 곧이어 이긴 쪽의 행을 읽는다.

```java
// PostgresAppUserRepository
@Override
@Transactional
public UUID ensureExists(String keycloakSubject) {
    return jpaRepository.findByKeycloakSubject(keycloakSubject)
            .map(AppUserEntity::getId)
            .orElseGet(() -> insertThenRead(keycloakSubject));
}

private UUID insertThenRead(String keycloakSubject) {
    jpaRepository.insertIfAbsent(UUID.randomUUID(), keycloakSubject, now());
    return jpaRepository.findByKeycloakSubject(keycloakSubject)  // 경합에서 진 쪽도 이긴 쪽 행을 본다
            .map(AppUserEntity::getId)
            .orElseThrow(() -> new IllegalStateException("사용자 삽입 후 조회 실패: " + keycloakSubject));
}
```

```java
// AppUserJpaRepository — 삽입을 원자적으로
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
        insert into app_user (id, keycloak_subject, created_at)
        values (:id, :subject, :createdAt)
        on conflict (keycloak_subject) do nothing
        """, nativeQuery = true)
void insertIfAbsent(@Param("id") UUID id, @Param("subject") String subject, @Param("createdAt") Instant createdAt);
```

## 3. 같이 가져가야 할 설계 결정 두 개

### (1) app_user에 역할(role)을 저장하지 않는다

컬럼이 셋뿐이다:

```sql
create table app_user (
    id               uuid primary key,
    keycloak_subject varchar(255) not null unique,
    created_at       timestamptz  not null default now()
);
```

역할은 **Keycloak이 부여하고 요청 시점에 접근 태그로 변환**한다(설계코드 S3). app_user에 role 컬럼을 두면
같은 사실이 Keycloak과 Postgres 두 곳에 살아 **이중 관리**가 되고, 둘이 어긋나면 권한 판단의 근원이
갈린다.

→ 팀이 이번에 겪은 "가입 직후 USER role 없어 403"을 **Keycloak Default roles = USER로 푼 것이 정답
방향**이다. 그걸 app_user에 복제하지 않는 게 llmhub 결정과 일치한다. 권한은 Keycloak 한 곳에서만.

### (2) 블로킹 JPA 호출은 격리 스케줄러에서 부른다

WebFlux(논블로킹)와 JPA(블로킹)가 공존하므로, `ensureExists` 호출은 이벤트 루프에서 직접 부르면 안 된다.
전부 격리 래퍼로 감싼다(설계코드 S13 / 경계 E12):

```java
return Blocking.call(() -> {
    UUID userId = userRepository.ensureExists(jwt.getSubject());
    return Map.of("id", historyRepository.createSession(userId, title).toString());
});
```

옹기종기도 WebFlux면 이 배선이 필요하다. MVC(블로킹 웹)라면 이 항목은 해당 없음.

---

## 팀 질문에 대한 직접 답

> **"이 로직 3번 항목에 포함해 내가 구현해도 될까, 아니면 app_user 소유나 인증 영역이 맡는 게 나을까?"**

llmhub 기준으로는 **셋 중 무엇의 하위도 아니다.** `common/user` 같은 **공용 컴포넌트로 빼고, 인터페이스
(`ensureExists(subject) → UUID`) 하나로 계약을 고정**한다. 채팅·세션·색인이 모두 같은 걸 부르니, 어느 한
모듈이 소유하면 나머지가 그 모듈을 가로질러 의존하게 된다. 구현은 누가 하든, 소비 측(채팅 3번 포함)은
인터페이스만 보면 된다.

## 참고 (llmhub 실제 파일)

- `backend/src/main/java/com/llmhub/common/user/` — 위 4개 파일
- `backend/src/main/resources/db/migration/V1__app_user_and_document.sql` — app_user 스키마
- 호출 지점: `chat/api/ChatController.java`, `chat/api/SessionController.java`, `idx/api/IndexController.java`

> 설계코드(S3·S13·E12 등)의 뜻은 DB 번들(`docs/syj/db-design-for-onggijonggi/README.md`)의 용어집 참조.
