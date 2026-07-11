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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
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

		// 문서 본문은 로그에 남기지 않는다 (SEC-3). 식별자와 크기만 남긴다.
		log.info(
				"색인 시작 docKey={} ext={} bytes={}",
				request.docKey(),
				extensionOf(request.filename()),
				request.content().length);

		// 원본 보관은 임베딩이 끝난 뒤에 한다. 임베딩이 실패하면 고아 파일이 남지 않는다.
		return run(
				request.docKey(),
				request.filename(),
				request.accessTags(),
				request.content(),
				() -> fileStorage.store(request.filename(), request.content()),
				request.uploadedBy());
	}

	/**
	 * 보관된 원본에서 다시 색인한다 (S16). 파일을 다시 받지 않는다.
	 *
	 * <p>업로드 검증을 하지 않는다. 이 바이트는 업로드 시점에 이미 허용목록을 통과했고, MIME 타입은 저장되어 있지 않다.
	 *
	 * <p>접근 태그는 요청이 아니라 <b>document의 현재 값</b>을 쓴다. document가 태그의 유일한 원천이다 (S18).
	 */
	public IndexResult reindex(String docKey) {
		DocumentRecord document =
				documentRepository.findByDocKey(docKey).orElseThrow(() -> new DocumentNotFoundException(docKey));

		byte[] content = fileStorage.read(document.storageKey());
		log.info("재색인 시작 docKey={} documentId={} bytes={}", docKey, document.id(), content.length);

		// uploadedBy는 null이다. 재색인은 보관된 원본을 다시 읽을 뿐, 누가 다시 올린 것이 아니다.
		return run(docKey, document.filename(), document.accessTags(), content, document::storageKey, null);
	}

	/** 현재 설정과 다른 임베딩 모델 <b>또는</b> 청킹 버전으로 색인된 문서들. 재색인 대상이다 (E9, E11). */
	public List<StaleDocument> staleDocuments() {
		String currentModel = embeddingClient.spec().model();
		String currentVersion = chunkingStrategy.version();
		return documentRepository.findStale(currentModel, currentVersion).stream()
				.map(
						d ->
								new StaleDocument(
										d.docKey(),
										d.filename(),
										d.embeddingModel(),
										d.chunkingVersion(),
										reasonFor(d, currentModel, currentVersion)))
				.toList();
	}

	private static StaleReason reasonFor(DocumentRecord d, String currentModel, String currentVersion) {
		boolean modelStale = !d.embeddingModel().equals(currentModel);
		boolean chunkingStale = !d.chunkingVersion().equals(currentVersion);
		if (modelStale && chunkingStale) {
			return StaleReason.MODEL_AND_CHUNKING;
		}
		return modelStale ? StaleReason.MODEL : StaleReason.CHUNKING;
	}

	/**
	 * 업로드와 재색인이 공유하는 파이프라인. 진입 경로가 달라도 같은 서비스를 지난다 (S1, E1).
	 *
	 * @param storageKey 원본의 저장 키. 업로드는 지금 저장하고, 재색인은 보관된 것을 그대로 쓴다. 임베딩이 끝난 뒤에야
	 *     평가된다.
	 * @param uploadedBy 파일을 올린 사람. 재색인은 {@code null}이며, 그때 기존 값이 유지된다.
	 */
	private IndexResult run(
			String docKey,
			String filename,
			List<String> accessTags,
			byte[] content,
			Supplier<String> storageKey,
			UUID uploadedBy) {

		DocumentParser parser = parserFor(extensionOf(filename));
		String text = parser.extractText(content, filename);
		List<Chunk> chunks = chunkingStrategy.chunk(text);
		if (chunks.isEmpty()) {
			throw new IllegalStateException("색인할 조각이 없다: " + filename);
		}
		log.debug("추출·청킹 완료 docKey={} textChars={} chunks={}", docKey, text.length(), chunks.size());

		EmbeddingSpec spec = embeddingClient.spec();
		requireCompatibleDimensions(spec);

		// ES에 쓰기 전에 임베딩을 전량 끝낸다. 여기서 실패하면 아무것도 쓰이지 않는다.
		List<float[]> vectors = embeddingClient.embed(chunks.stream().map(Chunk::text).toList());
		if (vectors.size() != chunks.size()) {
			throw new IllegalStateException(
					"임베딩 개수가 조각 개수와 다르다: %d != %d".formatted(vectors.size(), chunks.size()));
		}
		log.debug("임베딩 완료 docKey={} model={} vectors={}", docKey, spec.model(), vectors.size());

		// id는 PG 커밋보다 먼저 필요하다. doc_key에서 결정적으로 유도하므로 upsert 전에 조각을 조립할 수 있다.
		// document의 태그·이름은 upsert에 넘길 값과 같다 — 그 값으로 조각을 만든다.
		String documentId = DocumentId.of(docKey).toString();
		String indexingRunId = UUID.randomUUID().toString();
		List<IndexedChunk> indexed =
				assembler.assemble(
						new DocumentMetadata(documentId, filename, accessTags),
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
		chunkRepository.deleteStaleChunks(documentId, indexingRunId);

		// ES가 확정된 뒤에만 PG 메타데이터를 커밋한다. ES가 실패하면 여기 도달하지 못하므로,
		// 검색 불가인데 stale 목록에도 없는 유령 문서가 생기지 않는다 (R-3). 원본 보관(storageKey.get())도
		// 이 시점에 일어나 색인 실패 시 고아 파일이 남지 않는다.
		DocumentRecord document =
				documentRepository.upsert(
						docKey,
						filename,
						storageKey.get(),
						accessTags,
						spec.model(),
						// 임베딩 모델과 같다. 매 색인마다 지금 쓰는 전략의 버전을 기록한다 (E11).
						chunkingStrategy.version(),
						uploadedBy);

		log.info(
				"색인 완료 docKey={} documentId={} indexingRunId={} chunks={}",
				docKey,
				document.id(),
				indexingRunId,
				indexed.size());
		return new IndexResult(document.id(), indexingRunId, indexed.size());
	}

	/**
	 * 임베딩을 시작하기 <b>전에</b> 차원을 확인한다. {@code dense_vector} 차원은 인덱스 생성 시 고정되므로, 다르면 어차피
	 * 색인이 실패한다. 미리 멈추면 임베딩 비용을 쓰지 않고 구버전 조각도 건드리지 않는다 (S8-3).
	 */
	private void requireCompatibleDimensions(EmbeddingSpec spec) {
		OptionalInt indexed = chunkRepository.indexedDimensions();
		if (indexed.isPresent() && indexed.getAsInt() != spec.dimensions()) {
			// 응답은 409뿐이다. 이유는 로그에만 남으므로 반드시 남긴다 (S8-3, REL-3).
			log.warn(
					"임베딩 차원 불일치로 색인을 거부한다. 설정={} 인덱스={} model={} — 차원을 바꾸려면 새 인덱스가 필요하다 (docs/03)",
					spec.dimensions(),
					indexed.getAsInt(),
					spec.model());
			throw new EmbeddingDimensionMismatchException(indexed.getAsInt(), spec.dimensions());
		}
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
