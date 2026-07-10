package com.llmhub.idx.storage;

/**
 * 업로드 원본을 보관한다 (S16).
 *
 * <p>저장 백엔드는 교체 가능하다: 로컬 FS → 오브젝트 스토리지 (E14). 그래서 파일시스템 경로가 아니라 불투명한 저장 키를
 * 주고받는다.
 */
public interface FileStorage {

	/**
	 * 원본을 저장하고 불투명한 저장 키를 반환한다.
	 *
	 * <p>저장 경로는 시스템이 생성한다. {@code originalFilename}은 경로에 쓰이지 않는다 (SEC-4).
	 *
	 * @param originalFilename 사용자가 올린 파일명. 경로가 아니라 기록용이다.
	 * @param content 원본 바이트
	 * @return {@code document.original_path}에 기록할 저장 키
	 */
	String store(String originalFilename, byte[] content);

	/** 저장 키로 원본을 읽는다. 재색인은 이 원본에서 수행한다 (S16). */
	byte[] read(String key);
}
