package com.llmhub.idx.service;

import com.llmhub.idx.chunking.Chunk;
import com.llmhub.idx.chunking.ChunkingStrategy;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.idx.index.ChunkAssembler;
import com.llmhub.idx.index.ChunkRepository;
import com.llmhub.idx.index.DocumentMetadata;
import com.llmhub.idx.index.EmbeddedChunk;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.idx.index.IndexedChunk;
import com.llmhub.idx.parser.DocumentParser;
import com.llmhub.idx.storage.FileStorage;
import com.llmhub.idx.upload.UploadValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 색인 파이프라인: 검증 → 원본 보관 → 추출 → 청킹 → 임베딩 → 저장.
 *
 * <p>진입 경로(API/CLI/UI)와 분리된 서비스다. 어떤 진입도 이 서비스를 호출한다 (S1, E1).
 *
 * <p><b>임베딩을 전량 마친 뒤에 bulk 색인한다.</b> 임베딩 도중 실패하면 ES에 아무것도 쓰이지 않는다. 부분 색인된 신버전
 * 조각이 구버전과 함께 검색되어 중복 근거를 만드는 것을 막는다 (S17 × S8-3).
 */
public final class IndexingService {

	private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

	private final UploadValidator validator;
	private final FileStorage fileStorage;
	private final List<DocumentParser> parsers;
	private final ChunkingStrategy chunkingStrategy;
	private final EmbeddingClient embeddingClient;
	private final ChunkRepository chunkRepository;
	private final DocumentRepository documentRepository;
	private final Clock clock;
	private final ChunkAssembler assembler = new ChunkAssembler();

	public IndexingService(
			UploadValidator validator,
			FileStorage fileStorage,
			List<DocumentParser> parsers,
			ChunkingStrategy chunkingStrategy,
			EmbeddingClient embeddingClient,
			ChunkRepository chunkRepository,
			DocumentRepository documentRepository,
			Clock clock) {
		this.validator = validator;
		this.fileStorage = fileStorage;
		this.parsers = List.copyOf(parsers);
		this.chunkingStrategy = chunkingStrategy;
		this.embeddingClient = embeddingClient;
		this.chunkRepository = chunkRepository;
		this.documentRepository = documentRepository;
		this.clock = clock;
	}

	public IndexResult index(IndexRequest request) {
		validator.validate(request.filename(), request.contentType(), request.content().length);

		String extension = extensionOf(request.filename());
		// 문서 본문은 로그에 남기지 않는다 (SEC-3). 식별자와 크기만 남긴다.
		log.info("색인 시작 docKey={} ext={} bytes={}", request.docKey(), extension, request.content().length);

		DocumentParser parser = parserFor(extension);
		String text = parser.extractText(request.content(), request.filename());
		List<Chunk> chunks = chunkingStrategy.chunk(text);
		if (chunks.isEmpty()) {
			throw new IllegalStateException("색인할 조각이 없다: " + request.filename());
		}
		log.debug("추출·청킹 완료 docKey={} textChars={} chunks={}", request.docKey(), text.length(), chunks.size());

		EmbeddingSpec spec = embeddingClient.spec();
		// ES에 쓰기 전에 임베딩을 전량 끝낸다. 여기서 실패하면 아무것도 쓰이지 않는다.
		List<float[]> vectors = embeddingClient.embed(chunks.stream().map(Chunk::text).toList());
		if (vectors.size() != chunks.size()) {
			throw new IllegalStateException(
					"임베딩 개수가 조각 개수와 다르다: %d != %d".formatted(vectors.size(), chunks.size()));
		}
		log.debug("임베딩 완료 docKey={} model={} vectors={}", request.docKey(), spec.model(), vectors.size());

		String storageKey = fileStorage.store(request.filename(), request.content());
		DocumentRecord document =
				documentRepository.upsert(
						request.docKey(), request.filename(), storageKey, request.accessTags(), spec.model());

		String indexingRunId = UUID.randomUUID().toString();
		List<IndexedChunk> indexed =
				assembler.assemble(
						new DocumentMetadata(document.id(), document.filename(), document.accessTags()),
						chunks,
						spec,
						indexingRunId,
						Instant.now(clock));

		chunkRepository.createIndexIfMissing(spec);
		chunkRepository.indexAll(
				IntStream.range(0, indexed.size())
						.mapToObj(i -> new EmbeddedChunk(indexed.get(i), vectors.get(i)))
						.toList());

		// 순서가 전부다. 신버전 색인이 끝난 뒤에만 구버전을 지운다.
		// 역순이면 색인 도중 장애가 났을 때 문서가 통째로 사라진다 (S17 × S8-3).
		chunkRepository.deleteStaleChunks(document.id(), indexingRunId);

		log.info(
				"색인 완료 docKey={} documentId={} indexingRunId={} chunks={}",
				request.docKey(),
				document.id(),
				indexingRunId,
				indexed.size());
		return new IndexResult(document.id(), indexingRunId, indexed.size());
	}

	private DocumentParser parserFor(String extension) {
		return parsers.stream()
				.filter(p -> p.supports(extension))
				.findFirst()
				.orElseThrow(
						() -> new IllegalStateException("해당 확장자를 다루는 파서가 없다: " + extension));
	}

	private static String extensionOf(String filename) {
		int lastDot = filename.lastIndexOf('.');
		return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
	}
}
