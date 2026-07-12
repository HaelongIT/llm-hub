package com.llmhub.idx.upload;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;

/**
 * 업로드된 파일의 확장자·MIME·크기를 검증한다 (SEC-4).
 *
 * <p><b>허용목록 방식</b>이다. 차단목록이면 새 포맷이 조용히 통과한다. 허용목록은 설정값으로 주입한다 (REL-4).
 *
 * <p>확장자만으로는 위장을 막지 못하므로 확장자마다 허용되는 MIME 타입을 함께 못박는다. 확장자·MIME은 클라이언트가
 * 준 값이라 위조되므로, 방어심층으로 내용의 magic bytes를 스니핑해 실행파일 같은 위험 타입을 추가로 막는다 (리뷰 L-11).
 */
public final class UploadValidator {

	/** 확장자·MIME을 무엇으로 위장하든 내용이 이것이면 거부한다. Tika가 magic bytes로 탐지한 실제 타입 기준. */
	private static final Set<String> DANGEROUS_CONTENT_TYPES =
			Set.of(
					"application/x-executable",
					"application/x-elf",
					"application/x-sharedlib",
					"application/x-object",
					"application/x-dosexec",
					"application/x-msdownload",
					"application/vnd.microsoft.portable-executable",
					"application/x-mach-o",
					"application/x-mach-binary",
					"text/x-shellscript",
					"application/x-sh",
					"application/x-bat",
					"application/x-msi");

	private final Tika tika = new Tika();
	private final Map<String, Set<String>> allowedExtensionToMimeTypes;
	private final long maxBytes;

	public UploadValidator(Map<String, Set<String>> allowedExtensionToMimeTypes, long maxBytes) {
		this.allowedExtensionToMimeTypes = Map.copyOf(allowedExtensionToMimeTypes);
		this.maxBytes = maxBytes;
	}

	/**
	 * @throws UploadRejectedException 확장자·MIME이 허용목록에 없거나, 크기가 범위를 벗어나거나, 내용의 실제
	 *     타입(magic bytes)이 실행파일 같은 위험 타입인 경우
	 */
	public void validate(String filename, String contentType, byte[] content) {
		long sizeBytes = content.length;
		String extension = extensionOf(filename);
		Set<String> allowedMimeTypes = allowedExtensionToMimeTypes.get(extension);
		if (allowedMimeTypes == null) {
			throw new UploadRejectedException("허용되지 않은 확장자: " + extension);
		}
		if (!allowedMimeTypes.contains(contentType)) {
			throw new UploadRejectedException(
					"확장자 %s 에 허용되지 않은 MIME 타입: %s".formatted(extension, contentType));
		}
		if (sizeBytes <= 0) {
			throw new UploadRejectedException("빈 파일은 색인할 수 없다");
		}
		if (sizeBytes > maxBytes) {
			throw new UploadRejectedException("최대 크기 %d 바이트를 초과했다: %d".formatted(maxBytes, sizeBytes));
		}
		// 내용의 실제 타입을 magic bytes로 본다. 파일명 힌트 없이 순수 magic으로 탐지해 확장자 편향을 배제한다.
		// Tika가 모르는 포맷(예: hwp)은 octet-stream으로 잡혀 여기서 걸리지 않는다 — 정상 문서를 막지 않는다.
		String detected = tika.detect(content);
		if (DANGEROUS_CONTENT_TYPES.contains(detected)) {
			throw new UploadRejectedException("내용이 실행파일 등 위험한 타입이다: %s (확장자 %s)".formatted(detected, extension));
		}
	}

	/** 마지막 점 이후를 확장자로 본다. {@code 규정.pdf.exe} 의 확장자는 {@code exe} 다. */
	private static String extensionOf(String filename) {
		int lastDot = filename.lastIndexOf('.');
		if (lastDot < 0 || lastDot == filename.length() - 1) {
			throw new UploadRejectedException("확장자가 없다: " + filename);
		}
		return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
	}
}
