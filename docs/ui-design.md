# UI 디자인 참고 지도 — 열람실(Reading Room)

> **이 문서는 참고 지도이지 소스가 아니다.** UI를 만지기 전 여기서 방향을 잡되, 값·동작은 **반드시 실제 코드**
> (`frontend/app/globals.css`, `frontend/components/*`, `frontend/lib/*`)에서 확정한다. 이 문서만 보고 지레짐작하지 않는다.
>
> **프론트 UI를 바꾸면 이 문서도 반드시 함께 갱신한다**(CLAUDE.md §6). 지도가 실제와 어긋나면 지도가 아니라 함정이 된다.
>
> 소유: 에이전트가 자유롭게 갱신하는 참고 지식(§8). pre-commit 잠금 대상 아님. 비밀정보는 두지 않는다(SEC-3).

---

## 1. 정체성·원칙

- **열람실(Reading Room).** 사내 문서에서 **근거를 대며** 답하는 조용한 자료실. 차가운 세이지-페이퍼 위에 잉크와 틸 그린.
- **근거가 주인공이다.** 근거 슬립은 답변 **위**에 온다(S6 — 근거는 서버 검색 결과에서만 나오고, 답변보다 먼저 도착). 이 배치가 정체성이다.
- **도메인 중립·조용한 톤.** 특정 업무 개념을 UI에 넣지 않는다. 화려함보다 읽기·신뢰.
- **대담함은 시그니처 한 곳에만.** 근거 슬립이 유일하게 또렷한 곳. 나머지(다크·진입·마크다운·컴포저)는 조용하고 절제되게.
- **모션은 거든다, 뽐내지 않는다.** 로드 1회의 정돈된 연출 + 슬립 순차 안착까지. **분산·앰비언트 애니메이션 금지**(AI티 남).
  `prefers-reduced-motion`을 항상 존중한다.
- **품질 바닥은 조용히 지킨다.** 모바일까지 반응형, 키보드 포커스 링 표시, reduced-motion 존중.

관련: `frontend/app/globals.css` 최상단 주석, CLAUDE.md §1(정체성), docs/02-decisions.md(S6).

## 2. 색 토큰 (`globals.css` `:root`)

모든 스타일이 이 토큰 위에 선다. 그래서 다크는 **토큰만 리매핑**하면 전 화면이 따라온다. **하드코딩 색을 쓰지 않는다.**

| 토큰 | 라이트 | 다크 | 쓰임 |
|---|---|---|---|
| `--paper` | `#e9ece6` | `#14201c` | 배경(세이지 페이퍼 / 밤엔 먹빛) |
| `--surface` | `#f3f5f0` | `#1c2723` | 카드·입력창 등 살짝 뜬 면 |
| `--ink` | `#17211f` | `#e6e9e2` | 본문 글자 |
| `--muted` | `#5c6660` | `#93a099` | 보조 글자·라벨 |
| `--rule` | `#cdd2ca` | `#2c3833` | 경계선·구분선 |
| `--grounded` | `#2f6f5b` | `#6cbca0` | 틸 액센트(브랜드·근거·강조). 다크는 대비 위해 밝힘 |
| `--grounded-weak` | `#dce7e1` | `#24352e` | 틸 워시(활성 세션·역할칩 배경) |
| `--alert` | `#9a5b2a` | `#cf8a52` | 경고·오류·삭제(따뜻한 갈/앰버) |

- 색 토큰은 **8개**. + `color-scheme`(light/dark)를 함께 넘겨 스크롤바·textarea 등 네이티브 컨트롤이 테마를 따른다.
- **토큰 플립의 이점:** 버튼이 `color:var(--paper)` on `background:var(--grounded)`라, 라이트/다크에서 두 토큰이 함께
  뒤집혀 "밝은 배경 위 어두운 글자" 대비가 **자동으로** 유지된다(`.gate__btn`, `.composer__send`). 새 요소도 토큰만 쓰면 다크가 공짜.

## 3. 서체 (`app/layout.tsx` — next/font)

- **본문·한글: IBM Plex Sans KR** → `--font-sans`(`--sans`).
- **시스템 데이터의 모노 목소리: IBM Plex Mono** → `--font-mono`(`--mono`). 위치·score·시각·id·라벨·브랜드 워드마크·순위번호에 쓴다.
- 같은 슈퍼패밀리라 함께 앉는다. 규칙: **사람이 읽는 답변 = 산세, 기계가 만든 데이터 = 모노.**

## 4. 레이아웃·간격 (`globals.css`)

- **셸(`.app`):** `grid-template-columns: var(--sidebar-w) 1fr`. 사이드바 `--sidebar-w` = 264px + 메인.
- **읽기 열:** `.thread`·`.composer__row`·`.composer__count`는 `max-width: 760px` 중앙 정렬. 답변은 이 폭 안에서 읽힌다.
- **간격 스케일:** `--s1` 4 / `--s2` 8 / `--s3` 12 / `--s4` 16 / `--s5` 24 / `--s6` 32 (px). 여백은 이 스케일로만.
- **모서리:** `--radius` 8, `--radius-lg` 10.
- **모바일(≤720px):** 사이드바가 상단으로 스택(`grid-template-rows: auto 1fr`, 사이드바 `max-height: 42vh`), thread·composer 패딩 축소.

## 5. 시그니처 — 근거 슬립 (`components/Chat.tsx` `Slip`/`Evidence`, `globals.css` `.slip*`·`.evidence*`)

유일하게 대담함을 쓰는 곳. '서가에서 관련도순으로 뽑은 카드'.

- **답변 위에 온다.** `.evidence`(라벨 `근거 N · 관련도순`) → 슬립들 → `.answer__head` → 답변.
- **좌측 틸 룰**(3px `--grounded`)이 쉼 상태의 시그니처. hover 시 진해지고 배경에 옅은 틸 워시.
- **순위번호**(`.slip__rank`, 모노 `01`·`02`…): 검색이 실제 관련도순이라 **순서를 담는 정보**지 장식이 아니다.
- **관련도 바**(`.scorebar` 56×4 + `.slip__num` 점수): 조용한 잉크 채움. 의미가 먼저, 수치는 보조.
- **긴 인용은 접는다**(`QUOTE_CLAMP` 180자 → "더 보기"/"접기").
- **순차 안착:** 슬립마다 `--i`(순위)를 넘겨 `animation-delay: calc(var(--i) * 40ms)` → 위에서부터 차례로.
- **빈 근거:** "관련 근거를 찾지 못했습니다. 다르게 물어보거나 접근 권한을 확인하세요."(`.evidence--empty`).

## 6. 컴포넌트 인벤토리 (클래스 → 파일)

| 컴포넌트 | 클래스 | 파일 |
|---|---|---|
| 로그인 게이트 | `.gate`·`.gate__brand`·`.gate__tagline`·`.gate__btn` | `app/page.tsx` |
| 앱 셸·사이드바 | `.app`·`.sidebar`·`.sidebar__brand`·`.sidebar__foot`·`.sidebar__actions` | `app/page.tsx` |
| 신원·역할칩 | `.sidebar__id`·`.sidebar__user`·`.rolechip`·`.signout` | `app/page.tsx` |
| 테마 토글 | `.themetoggle`(◐) | `components/ThemeToggle.tsx` |
| 세션 목록 | `.sessions`·`.sessions__group`·`.session`·`.session--active`·`.session__confirm` | `components/Sessions.tsx` |
| 새 대화(⌘K) | `.newchat`·`.newchat__kbd` | `components/Sessions.tsx` |
| 메인 헤더 | `.main__header` | `components/Chat.tsx` |
| 환영·예시칩 | `.welcome`·`.welcome__lead`·`.welcome__chips`·`.chip` | `components/Chat.tsx` |
| 메시지 | `.msg`·`.msg--user`·`.msg--assistant`·`.question`·`.msg__label` | `components/Chat.tsx` |
| 근거 슬립 | `.evidence`·`.slip`·`.slip__rank`·`.scorebar` | `components/Chat.tsx` |
| 답변(마크다운) | `.answer__head`·`.answer__copy`·`.prose`·`.answer__grounded` | `components/Chat.tsx` + `components/Markdown.tsx` |
| 스트리밍/생각 중 | `.streaming`·`.dots` | `components/Chat.tsx` |
| 오류 배너 | `.banner`·`.banner__action` | `components/Chat.tsx` |
| 컴포저 | `.composer`·`.composer__input`(textarea)·`.composer__send`·`.composer__stop`·`.composer__count` | `components/Chat.tsx` |

컴포저 동작: Enter 전송/Shift+Enter 줄바꿈(한글 조합 중 Enter는 확정), auto-height(max 168px), 3600자↑ 카운터·4000자↑ 비활성,
스트리밍 중 보내기→중지 토글(`useChat().stop()`), 하단 고정 스크롤(위로 올리면 해제).

## 7. 모션 (`globals.css` `@keyframes`)

| 키프레임 | 어디 |
|---|---|
| `slip-in` (+ `--i` 스태거) | 근거 슬립 안착 |
| `rise` | 게이트·환영 요소 계단식 등장 |
| `mark-in` | 게이트 워드마크 그려짐(scale) |
| `dot-pulse` | 생각 중 점 3개 |

전부 로드 1회 또는 도착 1회. **`@media (prefers-reduced-motion: reduce)`에서 이들 애니메이션을 모두 정지**시키는 블록을 유지한다 —
새 애니메이션을 넣으면 이 블록에도 반드시 추가한다.

## 8. 테마 구현

- **토큰 리매핑**(§2)이 전부다. `:root` 라이트, `:root[data-theme='dark']` 다크.
- **FOUC 가드:** `app/layout.tsx` `<head>`의 동기 인라인 스크립트가 페인트 전에 `data-theme`를 확정한다 —
  `localStorage['llmhub-theme']` 있으면 그 값, 없으면 `matchMedia('(prefers-color-scheme: dark)')`.
- **하이드레이션:** 스크립트가 서버 HTML에 없던 속성을 붙이므로 `<html>`에 `suppressHydrationWarning`. 이게 없으면 콘솔 경고.
- **토글:** `ThemeToggle.tsx`. 글리프는 테마 무관 고정(◐), 라벨(방향)만 마운트 후 확정 → 하이드레이션 안전.
  현재 테마는 `<html data-theme>`에서 읽고, 클릭 시 `nextTheme()`로 뒤집어 `dataset` + `localStorage`에 기록.
- 저장 키는 `lib/theme.ts`의 `THEME_STORAGE_KEY`. FOUC 스크립트 문자열과 **같은 값**이어야 한다.

## 9. 마크다운 답변 (`lib/markdown.ts`, `components/Markdown.tsx`, `.prose`)

- **자체 최소 파서, 의존성 0**(§8의 라이브러리 추가 회피). 순수 함수 `parseMarkdown` → 노드 트리 → `Markdown.tsx`가 React 엘리먼트로.
  지원: 제목·문단·목록·펜스 코드·인용 + 인라인 굵게(`**`)·기울임(`*`)·코드(`` ` ``)·링크.
- **안전장치:** href는 `^https?://`만 허용(그 외 스킴은 리터럴 텍스트 → XSS 차단). `dangerouslySetInnerHTML` 안 씀. `_`는 기울임 아님(snake_case 오인 방지).
- **스트리밍 중엔 평문**(`.answer`, `white-space: pre-wrap`), **완료된 답변만** `<Markdown>`로 렌더(미완성 구문 깜빡임 방지). 복사 버튼은 원문.
- 본문 타이포는 `.prose` 아래 요소 규칙(제목·목록·`code`·`pre`·`blockquote`·`a`) — 전부 토큰 색이라 다크 자동.

## 10. 관례

- **순수 CSS + CSS 변수. Tailwind·CSS-in-JS 없음.** 스타일은 전부 `globals.css`에 산다. 색·간격은 토큰으로만.
- **클래스 네이밍은 BEM-ish `.block__elem--mod`** (예: `.sidebar__foot`, `.slip__rank`, `.session--active`, `.msg--assistant`).
- **카피 보이스:** 한국어, 능동태·문장 대소문자 없는 자연스러운 문장, 근거·행동 우선. 오류/빈 화면은 "무엇을·어떻게"를 인터페이스 목소리로.
  버튼 라벨은 실제 일어나는 일(보내기/중지/복사/삭제). 상세: docs/00, frontend-design 스킬의 writing 지침.
- **파일 지도:**
  - `app/globals.css` — 토큰 + **전 스타일**(여기 하나에 모인다).
  - `app/layout.tsx` — 폰트 주입, FOUC 가드, `SessionProvider`.
  - `app/page.tsx` — 게이트 + 앱 셸(서버 컴포넌트, 세션 판정).
  - `components/Chat.tsx` — 스레드·슬립·답변·컴포저(핵심 클라이언트).
  - `components/Sessions.tsx` — 세션 목록·생성·삭제.
  - `components/Markdown.tsx` — 노드 트리 → 엘리먼트. `components/ThemeToggle.tsx` — 테마 토글.
  - `lib/markdown.ts`(파서·테스트) · `lib/theme.ts`(nextTheme) · `lib/time.ts`(상대시각·날짜그룹) — 순수 함수, 각 `*.test.ts`.

## 11. 이 문서 사용법 (다시)

1. UI 작업 전 여기서 **방향**을 잡는다(팔레트·시그니처·관례·어디에 뭐가 있는지).
2. 하지만 **값·동작은 실제 코드에서 확정**한다. 이 지도가 코드와 어긋날 수 있고, 어긋났다면 코드가 옳다.
3. UI를 바꿨으면 **이 문서를 갱신**해 지도를 실제와 맞춘다(토큰·클래스·컴포넌트·관례가 바뀌면 해당 절을 고친다).
