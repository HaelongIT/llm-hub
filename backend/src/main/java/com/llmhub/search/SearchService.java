package com.llmhub.search;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 접근 태그로 필터된 하이브리드 검색을 수행하고 근거(sources)를 만든다.
 *
 * <p><b>이것이 응답 근거의 원천이다.</b> LLM 출력에서 근거를 만들지 않는다 (S6).
 *
 * <p>질문 임베딩은 색인과 <b>같은 모델</b>로 만든다. 다르면 에러 없이 검색 품질만 붕괴한다 (S8-4). 배선이 같은
 * {@link EmbeddingClient} 빈을 IDX와 공유하므로 어긋날 수 없다.
 *
 * <p>권한을 판단하지 않는다. AUTH가 앞단 게이트에서 확정한 태그를 소비만 한다 (S4).
 *
 * <p><b>블로킹이다.</b> 리액티브 흐름에서는 {@code Blocking.call}로 격리해 부른다 (S13).
 */
public final class SearchService {

	private static final Logger log = LoggerFactory.getLogger(SearchService.class);

	private final QueryBuilder queryBuilder;
	private final EmbeddingClient embeddingClient;
	private final ChunkSearchRepository repository;
	private final int topK;

	public SearchService(
			QueryBuilder queryBuilder,
			EmbeddingClient embeddingClient,
			ChunkSearchRepository repository,
			int topK) {
		this.queryBuilder = queryBuilder;
		this.embeddingClient = embeddingClient;
		this.repository = repository;
		this.topK = topK;
	}

	/**
	 * @param accessTags AUTH가 확정한 사용자 태그. 비어 있으면 어떤 조각과도 교집합이 없다.
	 */
	public List<Source> search(String question, Set<String> accessTags) {
		if (accessTags.isEmpty()) {
			log.debug("접근 태그가 없어 검색을 건너뛴다. 인증은 됐지만 볼 수 있는 조각이 없다");
			return List.of();
		}
		String query = queryBuilder.build(question);
		float[] queryVector = embeddingClient.embed(List.of(query)).get(0);
		List<Source> sources = repository.search(query, queryVector, accessTags, topK);

		// 질문 원문은 로그에 남기지 않는다 (SEC-3). 근거를 못 찾은 원인을 좁히는 데 필요한 것은
		// 태그·topK·길이·결과 수다.
		log.info(
				"검색 완료 tags={} topK={} questionChars={} hits={}",
				accessTags.stream().sorted().toList(),
				topK,
				question.length(),
				sources.size());
		return sources;
	}

	/** 검색에 쓰는 임베딩 모델. 색인과 같아야 한다 (S8-4). */
	public EmbeddingSpec embeddingSpec() {
		return embeddingClient.spec();
	}
}
