package com.llmhub.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.search.ChunkSearchRepository;
import com.llmhub.search.ElasticsearchChunkSearchRepository;
import com.llmhub.search.LinearCombinationMerger;
import com.llmhub.search.PassthroughQueryBuilder;
import com.llmhub.search.QueryBuilder;
import com.llmhub.search.ResultMerger;
import com.llmhub.search.SearchService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 검색 배선.
 *
 * <p>{@link EmbeddingClient}는 IDX와 <b>같은 빈</b>이다. 색인과 검색의 임베딩 모델이 어긋날 수 없는 이유가
 * 이것이다 (S8-4).
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

	@Bean
	QueryBuilder queryBuilder() {
		return new PassthroughQueryBuilder();
	}

	@Bean
	ResultMerger resultMerger(SearchProperties properties) {
		return new LinearCombinationMerger(properties.bm25Boost(), properties.vectorBoost());
	}

	@Bean
	ChunkSearchRepository chunkSearchRepository(
			ElasticsearchClient client, SearchProperties properties, ResultMerger merger) {
		return new ElasticsearchChunkSearchRepository(client, properties.indexName(), merger);
	}

	@Bean
	SearchService searchService(
			QueryBuilder queryBuilder,
			EmbeddingClient embeddingClient,
			ChunkSearchRepository repository,
			SearchProperties properties) {
		return new SearchService(queryBuilder, embeddingClient, repository, properties.topK());
	}
}
