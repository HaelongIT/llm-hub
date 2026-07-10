# 07 · Git 워크플로우

> 작은 단위로 자주 커밋한다. 각 커밋은 그 자체로 의미가 있고, 테스트가 통과하는 상태여야 한다.

## 커밋 단위
- **TDD 사이클 단위**로 커밋: 하나의 시나리오에 대한 (실패하는 테스트 + 최소 구현 + 리팩터)를 **한 커밋**으로 묶는다.
- **red 상태로 커밋하지 않는다.** 커밋 시점의 코드는 언제나 **빌드·테스트가 통과**한다. `main`은 항상 green이다.
- 대신 **Red를 확인했다는 사실을 커밋 본문에 남긴다.** 실패 이유가 "미구현"이었음을 적는다(`docs/05`). 예: `Red 확인: LocalFileStorage 미구현으로 cannot find symbol.` 오타나 설정 오류로 실패한 것은 Red가 아니다.
- `test:` 타입은 **green인 테스트만 추가**할 때 쓴다(통합 테스트 보강, e2e 추가 등).
- **큰 기능을 한 커밋에 몰지 않는다.** 리뷰·되돌리기가 가능한 크기로.

## 커밋 메시지 (Conventional Commits)
```
<type>(<scope>): <요약>

<본문: 왜 이렇게 했는지, 관련 결정 S/E/REQ>

Tests: N passed
관련: S…, E…, REQ…
```
- **type:** `feat`(기능), `test`(테스트), `fix`(버그), `refactor`(동작 불변 개선), `docs`(문서), `chore`(설정·빌드), `perf`(성능).
- **scope:** 모듈 약칭 — `idx`, `auth`, `search`, `chat`, `audit`, `infra`, `docs`.
- 요약은 명령형 현재형, 한국어 또는 영어 일관되게.
- **`Tests: N passed` 트레일러는 기능 커밋에 필수다.** 실제 러너 출력에서 옮긴다. 증거가 없으면 green이 아니고, green이 아니면 커밋하지 않는다(`docs/08` D-5). 이번 커밋에서 연 보류 질문이 있으면 `OQ-NNN`도 본문에 적는다.

예 — TDD 사이클 하나를 묶은 커밋:
```
feat(search): 접근 태그 교집합 필터 구현

REQ-SEARCH 시나리오: restricted 조각이 USER 검색에서 배제된다.

사용자 태그 ∩ 조각 access_tags 필터를 ES 쿼리에 추가.
권한 판단은 하지 않고 AUTH가 확정한 태그를 소비만.

Red 확인: 태그 필터 미구현이라 restricted 조각이 결과에 포함됨.

Tests: 42 passed
관련: S4, SEC-2
```

예 — green인 테스트만 추가하는 커밋:
```
test(idx): PDF 업로드 end-to-end 색인 검증

대역이 아니라 실제 ES에 쓴다. 매핑·직렬화·nori가 맞물리는지는
진짜 엔진에서만 드러난다.

Tests: 61 passed
관련: REQ-IDX, S7
```

## 커밋 순서 권장 (모듈 착수 시)
1. `chore`: 모듈 스캐폴딩·의존성.
2. `feat`: 첫 시나리오 — 실패하는 테스트를 쓰고, Red를 눈으로 확인하고, 최소 구현으로 통과시킨 뒤 한 커밋으로. 본문에 Red 확인을 적는다.
3. `refactor`: 정리(동작 불변, 스위트 green 유지).
4. `docs`: LEARNINGS·문서 갱신(교훈 있으면).
→ 다음 시나리오 반복.

## 브랜치 (선택)
- v0 뼈대는 단순하게 진행 가능. 모듈별 작업이 커지면 `feat/<module>` 브랜치 사용.
- main은 항상 테스트 통과 상태 유지.

## 금지
- 비밀정보 커밋(`.env`, 키, 토큰) — `.gitignore`로 차단, `.env.example`만.
- 테스트 통과 안 되는 상태를 "임시로" main에 병합.
- 여러 모듈의 변경을 한 커밋에 섞기.
- 의미 없는 메시지(`update`, `fix bug`, `wip`)만 남기기.

## .gitignore 필수 항목 (초기 설정)
```
.env
*.local
/build, /target, /node_modules
/data (로컬 볼륨: ES/PG/원본파일)
*.key, *.pem
```
