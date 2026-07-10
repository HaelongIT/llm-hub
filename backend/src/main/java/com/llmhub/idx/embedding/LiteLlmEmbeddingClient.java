package com.llmhub.idx.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.llmhub.idx.index.EmbeddingSpec;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * LiteLLM 게이트웨이의 OpenAI 호환 {@code /v1/embeddings}를 호출한다.
 *
 * <p>코어는 모델명 파라미터만 전달한다. 라우팅·페일오버는 게이트웨이 책임이다 (S8-1, E7).
 *
 * <p><b>블로킹이다.</b> 리액티브 흐름에서는 {@code Blocking.call}로 격리해 부른다 (S13).
 */
public final class LiteLlmEmbeddingClient implements EmbeddingClient {

	private final RestClient restClient;
	private final EmbeddingSpec spec;

	public LiteLlmEmbeddingClient(String baseUrl, String apiKey, EmbeddingSpec spec) {
		this.restClient =
				RestClient.builder()
						.baseUrl(baseUrl)
						.defaultHeader("Authorization", "Bearer " + apiKey)
						.build();
		this.spec = spec;
	}

	@Override
	public EmbeddingSpec spec() {
		return spec;
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		if (texts.isEmpty()) {
			return List.of();
		}
		EmbeddingResponse response;
		try {
			response =
					restClient
							.post()
							.uri("/v1/embeddings")
							.contentType(MediaType.APPLICATION_JSON)
							.body(Map.of("model", spec.model(), "input", texts))
							.retrieve()
							.body(EmbeddingResponse.class);
		} catch (RuntimeException e) {
			throw new EmbeddingException("임베딩 게이트웨이 호출 실패: " + spec.model(), e);
		}
		return toVectors(response, texts.size());
	}

	private List<float[]> toVectors(EmbeddingResponse response, int expectedCount) {
		if (response == null || response.data() == null) {
			throw new EmbeddingException("임베딩 응답이 비어 있다");
		}
		if (response.data().size() != expectedCount) {
			throw new EmbeddingException(
					"임베딩 개수가 입력 개수와 다르다: %d != %d".formatted(response.data().size(), expectedCount));
		}
		return response.data().stream()
				.sorted(java.util.Comparator.comparingInt(EmbeddingData::index))
				.map(data -> toVector(data.embedding()))
				.toList();
	}

	private float[] toVector(List<Double> values) {
		if (values == null || values.size() != spec.dimensions()) {
			// 차원이 어긋나면 검색이 에러 없이 조용히 붕괴한다. 여기서 시끄럽게 실패한다 (S8-4).
			throw new EmbeddingException(
					"임베딩 차원이 설정과 다르다: %s != %d".formatted(values == null ? "null" : values.size(), spec.dimensions()));
		}
		float[] vector = new float[values.size()];
		for (int i = 0; i < values.size(); i++) {
			vector[i] = values.get(i).floatValue();
		}
		return vector;
	}

	private record EmbeddingResponse(@JsonProperty("data") List<EmbeddingData> data) {}

	private record EmbeddingData(
			@JsonProperty("index") int index, @JsonProperty("embedding") List<Double> embedding) {}
}
