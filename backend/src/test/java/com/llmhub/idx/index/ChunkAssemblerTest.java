package com.llmhub.idx.index;

import com.llmhub.common.embedding.EmbeddingSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmhub.idx.chunking.Chunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-IDX 시나리오: 조각 {@code access_tags}가 상위 document의 {@code access_tags}와 일치한다.
 *
 * <p>S18: 접근 태그의 유일한 원천은 document다. 조각의 태그는 색인 시 복사된 <b>사본</b>이다. 검색 필터는 사본을
 * 보지만 진실은 document에 있다.
 *
 * <p>S7: 각 조각은 필수 메타데이터 7종을 빠짐없이 가진다.
 */
class ChunkAssemblerTest {

	private static final Instant 색인시각 = Instant.parse("2026-07-10T02:00:00Z");
	private static final String 실행ID = "run-0001";

	private static final EmbeddingSpec 임베딩 = new EmbeddingSpec("bge-m3", 1024);

	private static final DocumentMetadata 문서 =
			new DocumentMetadata("doc-1", "인사규정.pdf", List.of("public", "restricted"));

	private static final List<Chunk> 조각들 =
			List.of(new Chunk("연차휴가는 15일이다.", "0"), new Chunk("휴가는 1년간 행사한다.", "1"));

	private final ChunkAssembler assembler = new ChunkAssembler();

	@Test
	@DisplayName("조각의 access_tags는 document의 access_tags와 일치한다")
	void 조각_태그는_문서_태그의_사본이다() {
		List<IndexedChunk> indexed = assembler.assemble(문서, 조각들, 임베딩, 실행ID, 색인시각);

		assertThat(indexed)
				.allSatisfy(c -> assertThat(c.accessTags()).containsExactlyElementsOf(문서.accessTags()));
	}

	@Test
	@DisplayName("document의 태그 목록을 나중에 바꿔도 이미 만들어진 조각은 흔들리지 않는다")
	void 태그는_방어적으로_복사된다() {
		List<String> 가변_태그 = new ArrayList<>(List.of("public"));
		DocumentMetadata 가변_문서 = new DocumentMetadata("doc-2", "규정.pdf", 가변_태그);

		List<IndexedChunk> indexed = assembler.assemble(가변_문서, 조각들, 임베딩, 실행ID, 색인시각);
		가변_태그.add("restricted");

		assertThat(indexed)
				.as("사본이 원천의 변화를 몰래 따라가면 색인 시점의 권한 스냅샷이 무너진다")
				.allSatisfy(c -> assertThat(c.accessTags()).containsExactly("public"));
	}

	@Test
	@DisplayName("모든 조각이 필수 메타데이터 7종을 갖는다")
	void 메타데이터_7종이_모두_있다() {
		List<IndexedChunk> indexed = assembler.assemble(문서, 조각들, 임베딩, 실행ID, 색인시각);

		assertThat(indexed).allSatisfy(c -> {
			assertThat(c.documentId()).isNotBlank();
			assertThat(c.documentName()).isNotBlank();
			assertThat(c.location()).isNotBlank();
			assertThat(c.accessTags()).isNotEmpty();
			assertThat(c.indexedAt()).isNotNull();
			assertThat(c.embeddingModel()).isNotBlank();
			assertThat(c.embeddingDim()).isPositive();
		});
	}

	@Test
	@DisplayName("조각의 원문과 위치정보가 그대로 실린다")
	void 원문과_위치정보가_보존된다() {
		List<IndexedChunk> indexed = assembler.assemble(문서, 조각들, 임베딩, 실행ID, 색인시각);

		assertThat(indexed).extracting(IndexedChunk::text).containsExactly("연차휴가는 15일이다.", "휴가는 1년간 행사한다.");
		assertThat(indexed).extracting(IndexedChunk::location).containsExactly("0", "1");
	}

	@Test
	@DisplayName("한 번의 색인 실행에서 만들어진 조각은 같은 indexing_run_id를 갖는다")
	void 같은_실행의_조각은_같은_실행ID를_갖는다() {
		List<IndexedChunk> indexed = assembler.assemble(문서, 조각들, 임베딩, 실행ID, 색인시각);

		assertThat(indexed)
				.as("교체 시 구버전 조각을 이 값으로 식별해 삭제한다 (S17)")
				.allSatisfy(c -> assertThat(c.indexingRunId()).isEqualTo(실행ID));
	}

	@Test
	@DisplayName("색인에 쓴 임베딩 모델과 차원이 조각마다 기록된다")
	void 임베딩_모델과_차원이_기록된다() {
		List<IndexedChunk> indexed = assembler.assemble(문서, 조각들, 임베딩, 실행ID, 색인시각);

		assertThat(indexed).allSatisfy(c -> {
			assertThat(c.embeddingModel()).isEqualTo("bge-m3");
			assertThat(c.embeddingDim()).isEqualTo(1024);
		});
	}

	@Test
	@DisplayName("조각이 없으면 색인할 것도 없다")
	void 조각이_없으면_빈_결과다() {
		assertThat(assembler.assemble(문서, List.of(), 임베딩, 실행ID, 색인시각)).isEmpty();
	}

	@Test
	@DisplayName("접근 태그가 없는 문서는 색인을 거부한다")
	void 태그가_없는_문서를_거부한다() {
		DocumentMetadata 태그없는_문서 = new DocumentMetadata("doc-3", "고아.pdf", List.of());

		assertThatThrownBy(() -> assembler.assemble(태그없는_문서, 조각들, 임베딩, 실행ID, 색인시각))
				.as("태그가 없으면 어떤 사용자에게도 검색되지 않는다. 조용히 사라진 문서가 된다")
				.isInstanceOf(IllegalArgumentException.class);
	}
}
