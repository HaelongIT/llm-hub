package com.llmhub.idx.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.idx.service.DocumentRecord;
import com.llmhub.idx.service.DocumentRepository;
import com.llmhub.support.PostgresInitializer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * docs/03의 {@code document} 테이블 불변식.
 *
 * <p>S17: 같은 {@code doc_key}는 같은 document다. 재업로드는 행을 갱신할 뿐 새로 만들지 않는다 — 그래야
 * 조각들이 하나의 {@code document_id} 아래 모인다.
 *
 * <p>S18: {@code access_tags}는 접근 태그의 유일한 원천이다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PostgresInitializer.class)
class PostgresDocumentRepositoryTest {

	@Autowired private DocumentRepository repository;
	@Autowired private DocumentJpaRepository jpaRepository;

	@Test
	@DisplayName("새 doc_key는 새 document로 저장된다")
	void 새_문서를_저장한다() {
		DocumentRecord 저장됨 =
				repository.upsert("규정-2026", "인사규정.pdf", "key-1", List.of("public"), "bge-m3");

		assertThat(저장됨.id()).isNotBlank();
		assertThat(저장됨.docKey()).isEqualTo("규정-2026");
		assertThat(저장됨.filename()).isEqualTo("인사규정.pdf");
		assertThat(저장됨.storageKey()).isEqualTo("key-1");
		assertThat(저장됨.accessTags()).containsExactly("public");
		assertThat(저장됨.embeddingModel()).isEqualTo("bge-m3");
	}

	@Test
	@DisplayName("같은 doc_key로 다시 올리면 같은 id를 유지한 채 갱신된다")
	void 같은_doc_key는_같은_id를_유지한다() {
		DocumentRecord 구버전 = repository.upsert("교체-대상", "v1.pdf", "key-old", List.of("public"), "bge-m3");

		DocumentRecord 신버전 =
				repository.upsert("교체-대상", "v2.pdf", "key-new", List.of("public", "restricted"), "bge-m3");

		assertThat(신버전.id()).as("id가 바뀌면 조각들이 고아가 된다 (S17)").isEqualTo(구버전.id());
		assertThat(신버전.filename()).isEqualTo("v2.pdf");
		assertThat(신버전.storageKey()).isEqualTo("key-new");
		assertThat(신버전.accessTags()).containsExactlyInAnyOrder("public", "restricted");
		assertThat(jpaRepository.findByDocKey("교체-대상")).isPresent();
		assertThat(jpaRepository.findAll().stream().filter(d -> d.getDocKey().equals("교체-대상")))
				.as("같은 doc_key로 행이 두 개 생기면 안 된다")
				.hasSize(1);
	}

	@Test
	@DisplayName("접근 태그 배열이 그대로 보존된다")
	void 접근_태그_배열이_보존된다() {
		DocumentRecord 저장됨 =
				repository.upsert("태그-보존", "기밀.pdf", "key-2", List.of("restricted", "public"), "bge-m3");

		DocumentEntity 조회 = jpaRepository.findByDocKey("태그-보존").orElseThrow();
		assertThat(조회.getAccessTags()).containsExactlyInAnyOrder("restricted", "public");
		assertThat(저장됨.accessTags()).containsExactlyInAnyOrder("restricted", "public");
	}

	@Test
	@DisplayName("교체 시각이 갱신된다")
	void 교체_시각이_갱신된다() throws InterruptedException {
		repository.upsert("시각-추적", "v1.pdf", "key-a", List.of("public"), "bge-m3");
		DocumentEntity 처음 = jpaRepository.findByDocKey("시각-추적").orElseThrow();

		Thread.sleep(10);
		repository.upsert("시각-추적", "v2.pdf", "key-b", List.of("public"), "bge-m3");
		DocumentEntity 나중 = jpaRepository.findByDocKey("시각-추적").orElseThrow();

		assertThat(나중.getUpdatedAt()).isAfter(처음.getUpdatedAt());
		assertThat(나중.getCreatedAt()).as("생성 시각은 교체로 바뀌지 않는다").isEqualTo(처음.getCreatedAt());
	}
}
