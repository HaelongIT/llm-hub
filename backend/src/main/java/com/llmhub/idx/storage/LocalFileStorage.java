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
		requireSafeKey(key);
		try {
			return Files.readAllBytes(root.resolve(key));
		} catch (IOException e) {
			throw new UncheckedIOException("원본 읽기 실패: " + key, e);
		}
	}

	/**
	 * 키는 시스템이 생성한 단일 경로 세그먼트여야 한다.
	 *
	 * <p>오늘의 호출자는 모두 DB에 저장된 UUID를 넘기지만, 키가 오염되면 저장 루트 밖을 읽을 수 있다. 호출자를 믿는 대신
	 * 여기서 막는다 (SEC-4, OQ-001).
	 */
	private static void requireSafeKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("저장 키가 비어 있다");
		}
		if (key.contains("/") || key.contains("\\") || key.contains("..")) {
			throw new IllegalArgumentException("저장 키는 단일 경로 세그먼트여야 한다: " + key);
		}
	}
}
