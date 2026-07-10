package com.llmhub.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.embedding.EmbeddingClient;
import com.llmhub.common.embedding.EmbeddingSpec;
import com.llmhub.support.PostgresInitializer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * docs/05 정합성 테스트: 색인 임베딩 모델과 검색 임베딩 모델이 동일하다 (S8-4).
 *
 * <p>어긋나도 예외는 나지 않는다. 검색 품질만 조용히 붕괴한다. 그래서 "같은 값인가"가 아니라 <b>"같은 빈인가"</b>를 묻는다
 * — 값 비교는 두 설정이 우연히 같을 때도 통과하지만, 단일 빈은 어긋날 수 없다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PostgresInitializer.class)
class EmbeddingConsistencyTest {

	@Autowired private ApplicationContext context;
	@Autowired private SearchService searchService;
	@Autowired private EmbeddingSpec embeddingSpec;

	@Test
	@DisplayName("EmbeddingClient 빈이 하나뿐이다. 색인과 검색이 그것을 공유한다")
	void 임베딩_클라이언트_빈은_하나다() {
		Map<String, EmbeddingClient> beans = context.getBeansOfType(EmbeddingClient.class);

		assertThat(beans)
				.as("빈이 둘이면 색인과 검색이 서로 다른 모델을 쓸 수 있다 (S8-4)")
				.hasSize(1);
	}

	@Test
	@DisplayName("검색이 쓰는 임베딩 스펙이 설정된 스펙과 같은 객체다")
	void 검색_임베딩_스펙이_설정과_동일하다() {
		assertThat(searchService.embeddingSpec()).isSameAs(embeddingSpec);
	}
}
