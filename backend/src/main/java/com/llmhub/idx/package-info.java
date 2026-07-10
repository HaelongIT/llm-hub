/**
 * IDX — 색인 파이프라인. 업로드에서 추출·청킹·임베딩을 거쳐 ES 저장까지.
 *
 * <p>관리자 경로에 독립적으로 선다. 다른 어떤 모듈에도 의존하지 않으며,
 * SEARCH와는 코드가 아니라 ES 인덱스 스키마로만 만난다(임베딩 모델 일치가 그 계약, S8-4).
 *
 * @see <a href="../../../../../../docs/requirements/REQ-IDX-indexing.md">REQ-IDX</a>
 */
package com.llmhub.idx;
