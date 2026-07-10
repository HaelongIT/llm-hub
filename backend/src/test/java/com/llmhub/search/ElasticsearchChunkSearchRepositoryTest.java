package com.llmhub.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.idx.chunking.Chunk;
import com.llmhub.idx.index.ChunkAssembler;
import com.llmhub.idx.index.DocumentMetadata;
import com.llmhub.idx.index.ElasticsearchChunkRepository;
import com.llmhub.idx.index.EmbeddedChunk;
import com.llmhub.idx.index.EmbeddingSpec;
import com.llmhub.support.ElasticsearchTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-SEARCH 시나리오: nori 형태소 검색, BM25 정확 용어, 벡터 의미 유사, 접근 태그 필터.
 *
 * <p>실제 엔진에서만 드러나는 것들이다. 조사 처리, 점수 합산, pre-filter의 실제 효과는 대역으로 흉내 낼 수 없다.
 *
 * <p>SEC-2: 권한 없는 조각은 결과에서 제거되는 것이 아니라 <b>애초에 결과에 없다</b>.
 */
class ElasticsearchChunkSearchRepositoryTest {

	private static final String 인덱스 = "llmhub-chunks-search";
	private static final EmbeddingSpec 임베딩 = new EmbeddingSpec("stub-embedding", 3);
	private static final Instant 색인시각 = Instant.parse("2026-07-10T02:00:00Z");

	/** 서로 직교에 가까운 벡터. 의미 유사도를 결정적으로 만든다. */
	private static final float[] 휴가_벡터 = {1.0f, 0.0f, 0.0f};
	private static final float[] 보안_벡터 = {0.0f, 1.0f, 0.0f};

	private static ChunkSearchRepository repository;

	@BeforeAll
	static void 조각을_색인한다() {
		var client = ElasticsearchTestSupport.client();
		var writer = new ElasticsearchChunkRepository(client, 인덱스);
		writer.createIndexIfMissing(임베딩);

		색인한다(writer, "doc-휴가", "휴가규정.txt", List.of("public"), "연차휴가는 근로기준법에 따라 부여한다.", 휴가_벡터);
		색인한다(writer, "doc-코드", "코드표.txt", List.of("public"), "규정 코드 REG-2026-01 은 인사 규정이다.", 보안_벡터);
		색인한다(writer, "doc-기밀", "기밀.txt", List.of("restricted"), "연차휴가 관련 기밀 지침이다.", 휴가_벡터);

		repository = new ElasticsearchChunkSearchRepository(client, 인덱스, new LinearCombinationMerger(1.0f, 1.0f));
	}

	@Test
	@DisplayName("조사가 붙은 문서를 형태소로 찾는다 (nori)")
	void 조사가_붙어도_찾는다() {
		List<Source> 결과 = repository.search("연차휴가", 휴가_벡터, Set.of("public"), 5);

		assertThat(결과)
				.as("nori가 없으면 '연차휴가는'이 통째로 한 토큰이라 '연차휴가'로 안 잡힌다 (S15)")
				.extracting(Source::documentId)
				.contains("doc-휴가");
	}

	@Test
	@DisplayName("정확한 용어·코드로 물으면 BM25가 잡는다")
	void 정확한_코드를_BM25가_잡는다() {
		// 질문 벡터를 일부러 코드 문서와 먼 쪽으로 준다. 그래도 BM25가 잡아야 한다.
		List<Source> 결과 = repository.search("REG-2026-01", 휴가_벡터, Set.of("public"), 5);

		assertThat(결과).isNotEmpty();
		assertThat(결과.get(0).documentId())
				.as("고유명사·코드는 벡터가 약하다. BM25가 있어야 한다 (S11)")
				.isEqualTo("doc-코드");
	}

	@Test
	@DisplayName("어휘가 겹치지 않아도 벡터가 의미 유사 문서를 잡는다")
	void 벡터가_의미_유사를_잡는다() {
		// "쉬는날"은 어떤 문서와도 어휘가 겹치지 않는다. 오직 벡터만이 doc-휴가를 끌어올 수 있다.
		List<Source> 결과 = repository.search("쉬는날", 휴가_벡터, Set.of("public"), 5);

		assertThat(결과)
				.as("BM25만으로는 못 찾는다. 하이브리드의 존재 이유다 (S11)")
				.extracting(Source::documentId)
				.contains("doc-휴가");
	}

	@Test
	@DisplayName("USER({public})의 검색 결과에 restricted 조각이 아예 없다")
	void restricted는_USER에게_보이지_않는다() {
		List<Source> 결과 = repository.search("연차휴가", 휴가_벡터, Set.of("public"), 10);

		assertThat(결과)
				.as("응답에서 제거하는 것이 아니라 검색 단계에서 배제한다 (SEC-2)")
				.extracting(Source::documentId)
				.doesNotContain("doc-기밀");
	}

	@Test
	@DisplayName("ADMIN({public,restricted})은 restricted 조각을 본다")
	void restricted는_ADMIN에게_보인다() {
		List<Source> 결과 = repository.search("연차휴가", 휴가_벡터, Set.of("public", "restricted"), 10);

		assertThat(결과).extracting(Source::documentId).contains("doc-기밀");
	}

	@Test
	@DisplayName("태그가 비면 아무것도 검색되지 않는다")
	void 태그가_없으면_아무것도_안_나온다() {
		List<Source> 결과 = repository.search("연차휴가", 휴가_벡터, Set.of(), 10);

		assertThat(결과).as("빈 태그로는 어떤 조각과도 교집합이 없다").isEmpty();
	}

	@Test
	@DisplayName("sources의 각 항목이 실제 색인된 조각과 대응한다")
	void sources가_실제_조각과_대응한다() {
		List<Source> 결과 = repository.search("연차휴가", 휴가_벡터, Set.of("public"), 5);

		assertThat(결과).allSatisfy(source -> {
			assertThat(source.documentId()).isNotBlank();
			assertThat(source.documentName()).isNotBlank();
			assertThat(source.location()).isNotBlank();
			assertThat(source.text()).as("근거 원문은 서버가 색인한 조각에서 온다. LLM이 지어낸 것이 아니다 (S6)").isNotBlank();
			assertThat(source.score()).isPositive();
		});
		assertThat(결과)
				.filteredOn(s -> s.documentId().equals("doc-휴가"))
				.singleElement()
				.satisfies(s -> {
					assertThat(s.documentName()).isEqualTo("휴가규정.txt");
					assertThat(s.text()).isEqualTo("연차휴가는 근로기준법에 따라 부여한다.");
				});
	}

	private static void 색인한다(
			ElasticsearchChunkRepository writer,
			String documentId,
			String documentName,
			List<String> tags,
			String text,
			float[] vector) {
		var indexed =
				new ChunkAssembler()
						.assemble(
								new DocumentMetadata(documentId, documentName, tags),
								List.of(new Chunk(text, "0")),
								임베딩,
								"run-1",
								색인시각);
		writer.indexAll(indexed.stream().map(c -> new EmbeddedChunk(c, vector)).toList());
	}
}
