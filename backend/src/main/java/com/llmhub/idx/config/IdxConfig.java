package com.llmhub.idx.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.llmhub.idx.chunking.ChunkingStrategy;
import com.llmhub.idx.chunking.TokenChunkingStrategy;
import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.LiteLlmEmbeddingClient;
import com.llmhub.idx.index.ChunkRepository;
import com.llmhub.idx.index.ElasticsearchChunkRepository;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.idx.parser.DocumentParser;
import com.llmhub.idx.parser.HwpDocumentParser;
import com.llmhub.idx.parser.TikaDocumentParser;
import com.llmhub.idx.service.DocumentRepository;
import com.llmhub.idx.service.IndexingService;
import com.llmhub.idx.storage.FileStorage;
import com.llmhub.idx.storage.LocalFileStorage;
import com.llmhub.idx.upload.UploadValidator;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 색인 파이프라인 배선. 교체 지점은 전부 인터페이스 뒤에 있다 (E8, E9, E11, E14). */
@Configuration
@EnableConfigurationProperties({IdxProperties.class, EmbeddingProperties.class})
public class IdxConfig {

	@Bean
	EmbeddingSpec embeddingSpec(EmbeddingProperties properties) {
		return new EmbeddingSpec(properties.model(), properties.dimensions());
	}

	@Bean
	EmbeddingClient embeddingClient(EmbeddingProperties properties, EmbeddingSpec spec) {
		return new LiteLlmEmbeddingClient(properties.baseUrl(), properties.apiKey(), spec);
	}

	@Bean
	ElasticsearchClient elasticsearchClient(IdxProperties properties) {
		Rest5Client lowLevel = Rest5Client.builder(URI.create(properties.elasticsearchUrl())).build();
		return new ElasticsearchClient(new Rest5ClientTransport(lowLevel, new JacksonJsonpMapper()));
	}

	@Bean
	ChunkRepository chunkRepository(ElasticsearchClient client, IdxProperties properties) {
		return new ElasticsearchChunkRepository(client, properties.indexName());
	}

	@Bean
	FileStorage fileStorage(IdxProperties properties) {
		return new LocalFileStorage(Path.of(properties.storageRoot()));
	}

	@Bean
	ChunkingStrategy chunkingStrategy(IdxProperties properties) {
		return new TokenChunkingStrategy(properties.chunkSizeTokens());
	}

	@Bean
	List<DocumentParser> documentParsers() {
		return List.of(new TikaDocumentParser(), new HwpDocumentParser());
	}

	@Bean
	UploadValidator uploadValidator(IdxProperties properties) {
		return new UploadValidator(properties.allowedUploads(), properties.maxUploadBytes());
	}

	@Bean
	IndexingService indexingService(
			UploadValidator validator,
			FileStorage fileStorage,
			List<DocumentParser> parsers,
			ChunkingStrategy chunkingStrategy,
			EmbeddingClient embeddingClient,
			ChunkRepository chunkRepository,
			DocumentRepository documentRepository,
			Clock clock) {
		return new IndexingService(
				validator,
				fileStorage,
				parsers,
				chunkingStrategy,
				embeddingClient,
				chunkRepository,
				documentRepository,
				clock);
	}
}
