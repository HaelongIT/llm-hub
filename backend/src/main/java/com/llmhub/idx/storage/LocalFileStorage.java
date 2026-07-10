package com.llmhub.idx.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 로컬 파일시스템 볼륨에 원본을 보관한다 (v0, S16).
 *
 * <p>저장 키는 시스템이 생성한 UUID다. 사용자 파일명은 경로에 전혀 관여하지 않으므로 경로 조작이 구조적으로 불가능하다
 * (SEC-4).
 */
public final class LocalFileStorage implements FileStorage {

	private final Path root;

	public LocalFileStorage(Path root) {
		this.root = root;
	}

	@Override
	public String store(String originalFilename, byte[] content) {
		String key = UUID.randomUUID().toString();
		try {
			Files.createDirectories(root);
			Files.write(root.resolve(key), content);
		} catch (IOException e) {
			throw new UncheckedIOException("원본 저장 실패: " + originalFilename, e);
		}
		return key;
	}

	@Override
	public byte[] read(String key) {
		try {
			return Files.readAllBytes(root.resolve(key));
		} catch (IOException e) {
			throw new UncheckedIOException("원본 읽기 실패: " + key, e);
		}
	}
}
