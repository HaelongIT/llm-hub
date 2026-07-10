package com.llmhub.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-SEARCH: 검색 임베딩 모델이 설정값이고 색인과 동일하다 (S8-4).
 *
 * <p>E3: 쿼리 생성은 독립 단계다. v0는 마지막 질문 그대로이며, 향후 쿼리 재작성을 앞에 삽입할 수 있다.
 *
 * <p>S4: 이 계층은 권한을 판단하지 않는다. AUTH가 확정한 태그를 소비만 한다.
 */
class SearchServiceTest {

	private static final EmbeddingSpec 설정된_임베딩 = new EmbeddingSpec("bge-m3", 3);

	private final AtomicReference<String> 임베딩된_텍스트 = new AtomicReference<>();
	private final AtomicReference<Set<String>> 검색에_쓰인_태그 = new AtomicReference<>();
	private final AtomicReference<String> 검색에_쓰인_질의 = new AtomicReference<>();

	private final EmbeddingClient 임베딩_대역 =
			new EmbeddingClient() {
				@Override
				public EmbeddingSpec spec() {
					return 설정된_임베딩;
				}

				@Override
				public List<float[]> embed(List<String> texts) {
					임베딩된_텍스트.set(texts.get(0));
					return List.of(new float[] {0.1f, 0.2f, 0.3f});
				}
			};

	private final ChunkSearchRepository 저장소_대역 =
			(queryText, queryVector, accessTags, topK) -> {
				검색에_쓰인_질의.set(queryText);
				검색에_쓰인_태그.set(accessTags);
				return List.of(new Source("doc-1", "규정.txt", "0", "연차휴가는 15일이다.", 1.5));
			};

	private final SearchService service =
			new SearchService(new PassthroughQueryBuilder(), 임베딩_대역, 저장소_대역, 5);

	@Test
	@DisplayName("질문을 그대로 검색 쿼리로 쓴다 (v0는 쿼리 재작성 없음)")
	void 질문을_그대로_쿼리로_쓴다() {
		service.search("연차휴가는 며칠인가요?", Set.of("public"));

		assertThat(검색에_쓰인_질의).hasValue("연차휴가는 며칠인가요?");
	}

	@Test
	@DisplayName("질문 임베딩에 설정된 모델을 쓴다 (색인과 동일)")
	void 검색_임베딩_모델이_설정값이다() {
		service.search("연차휴가", Set.of("public"));

		assertThat(service.embeddingSpec())
				.as("색인과 검색의 임베딩 모델이 다르면 에러 없이 검색 품질만 붕괴한다 (S8-4)")
				.isEqualTo(설정된_임베딩);
		assertThat(임베딩된_텍스트).hasValue("연차휴가");
	}

	@Test
	@DisplayName("AUTH가 확정한 태그를 그대로 소비한다")
	void 태그를_소비만_한다() {
		service.search("연차휴가", Set.of("public", "restricted"));

		assertThat(검색에_쓰인_태그.get())
				.as("검색 계층은 권한을 재판단하지 않는다 (S4)")
				.containsExactlyInAnyOrder("public", "restricted");
	}

	@Test
	@DisplayName("근거는 검색된 조각에서 그대로 온다")
	void 근거가_검색_결과에서_온다() {
		List<Source> sources = service.search("연차휴가", Set.of("public"));

		assertThat(sources).singleElement().satisfies(s -> {
			assertThat(s.documentName()).isEqualTo("규정.txt");
			assertThat(s.text()).isEqualTo("연차휴가는 15일이다.");
		});
	}

	@Test
	@DisplayName("태그가 비면 게이트웨이를 부르지도 않는다")
	void 빈_태그는_임베딩도_하지_않는다() {
		List<Source> sources = service.search("연차휴가", Set.of());

		assertThat(sources).isEmpty();
		assertThat(임베딩된_텍스트).hasValue((String) null);
	}
}
