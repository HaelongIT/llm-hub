package com.llmhub.idx.upload;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업로드된 파일의 확장자·MIME·크기를 검증한다 (SEC-4).
 *
 * <p><b>허용목록 방식</b>이다. 차단목록이면 새 포맷이 조용히 통과한다. 허용목록은 설정값으로 주입한다 (REL-4).
 *
 * <p>확장자만으로는 위장을 막지 못하므로 확장자마다 허용되는 MIME 타입을 함께 못박는다.
 */
public final class UploadValidator {

	private final Map<String, Set<String>> allowedExtensionToMimeTypes;
	private final long maxBytes;

	public UploadValidator(Map<String, Set<String>> allowedExtensionToMimeTypes, long maxBytes) {
		this.allowedExtensionToMimeTypes = Map.copyOf(allowedExtensionToMimeTypes);
		this.maxBytes = maxBytes;
	}

	/**
	 * @throws UploadRejectedException 확장자·MIME이 허용목록에 없거나 크기가 범위를 벗어난 경우
	 */
	public void validate(String filename, String contentType, long sizeBytes) {
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
