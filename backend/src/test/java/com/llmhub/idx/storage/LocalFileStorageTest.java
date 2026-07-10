package com.llmhub.idx.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-IDX 시나리오: 파일명에 {@code ../}가 포함되어도 경로 조작이 차단되고 시스템 경로에 안전하게 저장된다.
 *
 * <p>SEC-4: 원본 저장 경로는 시스템이 생성한다. 사용자 입력 파일명을 그대로 경로에 쓰지 않는다.
 */
class LocalFileStorageTest {

	private static final byte[] 내용 = "민감한 내용".getBytes(UTF_8);

	@Test
	@DisplayName("파일명에 ../가 있어도 저장 루트를 벗어나지 않는다")
	void 경로_조작을_차단한다(@TempDir Path root) throws IOException {
		var storage = new LocalFileStorage(root);

		String key = storage.store("../../../etc/passwd", 내용);

		assertThat(key).as("저장 키에 상위 경로 참조가 남으면 안 된다").doesNotContain("..");
		assertThat(저장된_파일들(root))
				.as("루트 안에 정확히 한 개의 파일만 만들어져야 한다")
				.hasSize(1);
		assertThat(storage.read(key)).as("저장한 내용을 그대로 읽을 수 있어야 한다").isEqualTo(내용);
	}

	@Test
	@DisplayName("사용자 파일명을 경로에 그대로 쓰지 않는다")
	void 사용자_파일명을_경로로_쓰지_않는다(@TempDir Path root) throws IOException {
		var storage = new LocalFileStorage(root);

		String key = storage.store("2026년_인사규정.pdf", 내용);

		assertThat(key).as("경로는 시스템이 생성한다. 원본 파일명은 document 레코드에 따로 보관한다").doesNotContain("인사규정");
		assertThat(저장된_파일들(root)).singleElement().satisfies(p -> assertThat(p.getFileName().toString()).doesNotContain("인사규정"));
	}

	private static List<Path> 저장된_파일들(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile).toList();
		}
	}
}
