# REQ-IDX · 색인 파이프라인

## 책임
관리자가 올린 문서를 검색 가능한 상태로 만든다: 업로드 → 추출 → 청킹 → 임베딩 → 저장. 원본 보관. 재업로드 시 교체.

## 관련 결정
S1, S7, S8-2, S8-4, S11, S12, S15, S16, S17, S18 · 경계 E1, E6, E8, E9, E10, E11, E14, E16

## 인터페이스 (교체 가능 지점)
- `DocumentParser` — 포맷별 어댑터(Tika, hwplib). 입력: 파일. 출력: 텍스트(+가능하면 위치정보). (E8)
- `ChunkingStrategy` — 입력: 텍스트. 출력: 조각[]. v0=토큰 기준. (E11, E12는 아님)
- `EmbeddingClient` — 입력: 텍스트[]. 출력: 벡터[]. 게이트웨이 경유, 모델 고정. (E9)
- `FileStorage` — 원본 저장/조회. v0=로컬 FS. (E14)
- `ChunkRepository`(ES), `DocumentRepository`(PG) — 저장.

## 기능 명세
1. **업로드 수신** — 관리자(ADMIN)만. multipart. 크기·확장자·MIME 검증(허용목록). `doc_key` 필수.
2. **원본 보관** — 시스템 생성 경로에 저장(사용자 파일명 그대로 경로 사용 금지). `document.original_path`에 기록.
3. **추출** — 포맷에 맞는 `DocumentParser`로 텍스트 추출. hwp는 hwplib.
4. **청킹** — `ChunkingStrategy`로 조각화. 각 조각에 위치정보(페이지/섹션 또는 순번).
5. **임베딩** — `EmbeddingClient`로 벡터화. 사용한 모델명·차원 기록.
6. **저장** — 각 조각을 ES에 저장: 원문(`chunk_text`, nori) + 벡터 + 메타 7종 + `access_tags`(document에서 복사). document 레코드를 PG에 저장/갱신.
7. **교체(재업로드)** — 같은 `doc_key`면: **신버전 조각 색인 완료 → 그다음 구버전 조각 삭제**(순서 절대 준수). 실패 시 구버전 유지.

## 불변식
- 저장된 모든 조각은 메타 7종을 전부 가진다.
- 조각 `access_tags` = 상위 document `access_tags`.
- 같은 `doc_key` 2회 색인 후 구버전 조각 0개.
- 조각의 `embedding_model` = document의 `embedding_model` = 설정 임베딩 모델.

## 테스트 시나리오 (TDD)
- [ ] PDF 업로드 → 조각들이 ES에 저장되고 메타 7종 존재.
- [x] txt 업로드 → 색인 성공.
- [ ] hwp 업로드 → hwplib 경로로 추출·색인 성공.
- [ ] 비-ADMIN 업로드 시도 → 거부(403).
- [x] 허용 안 된 확장자 → 거부.
- [x] 조각 `access_tags`가 document `access_tags`와 일치.
- [ ] 같은 `doc_key` 재업로드 → 구버전 조각 사라지고 신버전만 존재.
- [ ] 재업로드 중 임베딩 단계 실패 → 구버전 조각이 여전히 검색됨(증발 없음).
- [x] 파일명에 `../` 포함 → 경로 조작 차단, 시스템 경로에 안전 저장.
- [x] 색인 임베딩 모델 = 설정값.

## 하지 않을 것 (v0)
비동기 색인 큐, 진행률 UI, 문서 버전 이력 보관(구버전 아카이브), hwp 외 특수 포맷 OCR. — 자리만 열어둠.
