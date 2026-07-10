package com.llmhub.idx.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * 토큰 크기 기준 기계적 청킹 (S12). 조각 경계는 항상 글자 경계다.
 *
 * <p>왜 Spring AI의 {@code TokenTextSplitter}를 쓰지 않는가: 그 구현은 텍스트를 바이트 수준 BPE 토큰으로
 * 인코딩한 뒤 토큰 목록을 잘라 각 조각을 디코딩한다. 한글 한 글자는 여러 토큰에 걸치므로 조각 경계가 글자 중간을 자르고, 디코딩
 * 결과에 U+FFFD 치환 문자가 남는다. 손상된 텍스트가 그대로 {@code chunk_text}로 색인되면 검색과 근거가 조용히 오염된다
 * (v0 배포 언어는 한국어다, S15).
 *
 * <p>그래서 반대로 한다. 토큰은 <b>크기를 재는 데만</b> 쓰고, 자르는 위치는 문장 → 단어 → 코드포인트 순으로 내려가며 찾는다.
 * 어느 경우에도 글자 안쪽을 자르지 않으므로 손상이 구조적으로 불가능하다.
 *
 * <p>교체 가능한 부품이다 (E11). 교체하면 재색인이 필요하다.
 */
public final class TokenChunkingStrategy implements ChunkingStrategy {

	/** 종결 문장부호까지 포함해 한 문장씩 끊는다. 마지막 문장은 부호가 없을 수 있다. */
	private static final Pattern SENTENCE = Pattern.compile("[^.!?\\n]*[.!?\\n]|[^.!?\\n]+");

	private final int chunkSizeTokens;
	private final TokenCountEstimator tokenCounter;

	public TokenChunkingStrategy(int chunkSizeTokens) {
		this(chunkSizeTokens, new JTokkitTokenCountEstimator());
	}

	TokenChunkingStrategy(int chunkSizeTokens, TokenCountEstimator tokenCounter) {
		if (chunkSizeTokens <= 0) {
			throw new IllegalArgumentException("청크 크기는 양수여야 한다: " + chunkSizeTokens);
		}
		this.chunkSizeTokens = chunkSizeTokens;
		this.tokenCounter = tokenCounter;
	}

	@Override
	public List<Chunk> chunk(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		List<String> texts = merge(splitIntoFittingSegments(text));
		return IntStream.range(0, texts.size())
				.mapToObj(i -> new Chunk(texts.get(i), String.valueOf(i)))
				.toList();
	}

	/** 인접 세그먼트를 청크 크기가 허용하는 만큼 greedy하게 합친다. */
	private List<String> merge(List<String> segments) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String segment : segments) {
			if (current.isEmpty()) {
				current.append(segment);
				continue;
			}
			String candidate = current + segment;
			if (countTokens(candidate) <= chunkSizeTokens) {
				current = new StringBuilder(candidate);
			} else {
				addIfNotBlank(chunks, current.toString());
				current = new StringBuilder(segment);
			}
		}
		addIfNotBlank(chunks, current.toString());
		return chunks;
	}

	/** 각 세그먼트가 청크 크기 안에 들어가도록 문장 → 단어 → 코드포인트 순으로 내려간다. */
	private List<String> splitIntoFittingSegments(String text) {
		List<String> fitting = new ArrayList<>();
		Matcher sentences = SENTENCE.matcher(text);
		while (sentences.find()) {
			splitUntilItFits(sentences.group(), fitting);
		}
		return fitting;
	}

	private void splitUntilItFits(String segment, List<String> out) {
		if (segment.isBlank()) {
			return;
		}
		if (countTokens(segment) <= chunkSizeTokens) {
			out.add(segment);
			return;
		}
		String[] words = segment.split("(?<=\\s)");
		if (words.length > 1) {
			for (String word : words) {
				splitUntilItFits(word, out);
			}
			return;
		}
		// 단어 하나가 청크 크기를 넘는다. 코드포인트 단위로 자른다 — 글자를 쪼개지 않는다.
		out.addAll(splitByCodePoints(segment));
	}

	private List<String> splitByCodePoints(String word) {
		List<String> pieces = new ArrayList<>();
		StringBuilder piece = new StringBuilder();
		for (int codePoint : word.codePoints().toArray()) {
			String candidate = piece.toString() + Character.toString(codePoint);
			if (!piece.isEmpty() && countTokens(candidate) > chunkSizeTokens) {
				pieces.add(piece.toString());
				piece = new StringBuilder(Character.toString(codePoint));
			} else {
				piece = new StringBuilder(candidate);
			}
		}
		addIfNotBlank(pieces, piece.toString());
		return pieces;
	}

	private int countTokens(String text) {
		return tokenCounter.estimate(text);
	}

	private static void addIfNotBlank(List<String> target, String text) {
		String stripped = text.strip();
		if (!stripped.isEmpty()) {
			target.add(stripped);
		}
	}
}
