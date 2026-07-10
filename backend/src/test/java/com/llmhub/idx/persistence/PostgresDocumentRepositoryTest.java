package com.llmhub.idx.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.common.user.AppUserRepository;
import com.llmhub.idx.service.DocumentRecord;
import com.llmhub.idx.service.DocumentRepository;
import com.llmhub.support.PostgresInitializer;
import java.util.List;
import java.util.UUID;
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
	@Autowired private AppUserRepository userRepository;

	@Test
	@DisplayName("새 doc_key는 새 document로 저장된다")
	void 새_문서를_저장한다() {
		DocumentRecord 저장됨 =
				repository.upsert("규정-2026", "인사규정.pdf", "key-1", List.of("public"), "bge-m3", null);

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
		DocumentRecord 구버전 = repository.upsert("교체-대상", "v1.pdf", "key-old", List.of("public"), "bge-m3", null);

		DocumentRecord 신버전 =
				repository.upsert("교체-대상", "v2.pdf", "key-new", List.of("public", "restricted"), "bge-m3", null);

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
				repository.upsert("태그-보존", "기밀.pdf", "key-2", List.of("restricted", "public"), "bge-m3", null);

		DocumentEntity 조회 = jpaRepository.findByDocKey("태그-보존").orElseThrow();
		assertThat(조회.getAccessTags()).containsExactlyInAnyOrder("restricted", "public");
		assertThat(저장됨.accessTags()).containsExactlyInAnyOrder("restricted", "public");
	}

	@Test
	@DisplayName("doc_key로 문서를 찾는다 — 재색인은 이 레코드의 원본을 다시 읽는다")
	void doc_key로_찾는다() {
		repository.upsert("조회-대상", "원본.pdf", "key-원본", List.of("public"), "bge-m3", null);

		assertThat(repository.findByDocKey("조회-대상"))
				.get()
				.satisfies(d -> assertThat(d.storageKey()).isEqualTo("key-원본"));
		assertThat(repository.findByDocKey("없는-키")).isEmpty();
	}

	@Test
	@DisplayName("재색인 대상은 현재 설정과 다른 모델로 색인된 문서다")
	void 재색인_대상을_찾는다() {
		// 이 테스트 클래스는 DB를 공유한다. 다른 테스트의 문서와 섞이지 않도록 고유한 모델명을 쓴다.
		repository.upsert("옛-모델-문서", "a.pdf", "key-a", List.of("public"), "모델-2024", null);
		repository.upsert("현-모델-문서", "b.pdf", "key-b", List.of("public"), "모델-2026", null);

		List<String> 대상 = repository.findStale("모델-2026").stream().map(DocumentRecord::docKey).toList();

		assertThat(대상).as("메타의 모델명으로 재색인 대상을 식별한다 (E9)").contains("옛-모델-문서");
		assertThat(대상).as("현재 모델로 색인된 문서는 재색인할 필요가 없다").doesNotContain("현-모델-문서");
	}

	// docs/03: uploaded_by FK→app_user. 누가 이 문서를 올렸는지가 유일하게 남는 자리다.

	@Test
	@DisplayName("업로드한 사람이 document에 기록된다")
	void 업로더가_기록된다() {
		UUID 올린이 = userRepository.ensureExists("subject-업로더");

		repository.upsert("업로더-기록", "a.pdf", "k1", List.of("public"), "bge-m3", 올린이);

		assertThat(jpaRepository.findByDocKey("업로더-기록").orElseThrow().getUploadedBy()).isEqualTo(올린이);
	}

	@Test
	@DisplayName("재색인은 업로더를 바꾸지 않는다 — 다시 올린 사람이 없다")
	void 재색인은_업로더를_유지한다() {
		UUID 올린이 = userRepository.ensureExists("subject-원래-업로더");
		repository.upsert("업로더-유지", "a.pdf", "k1", List.of("public"), "옛-모델", 올린이);

		// 재색인은 보관된 원본을 다시 읽을 뿐이다. 올린 사람은 그대로다.
		repository.upsert("업로더-유지", "a.pdf", "k1", List.of("public"), "새-모델", null);

		assertThat(jpaRepository.findByDocKey("업로더-유지").orElseThrow().getUploadedBy())
				.as("null은 '기존 값 유지'다. 재색인이 업로더를 지우면 안 된다")
				.isEqualTo(올린이);
	}

	@Test
	@DisplayName("재업로드는 업로더를 새로 올린 사람으로 갱신한다")
	void 재업로드는_업로더를_갱신한다() {
		UUID 처음_올린이 = userRepository.ensureExists("subject-처음");
		UUID 나중_올린이 = userRepository.ensureExists("subject-나중");
		repository.upsert("업로더-갱신", "v1.pdf", "k1", List.of("public"), "bge-m3", 처음_올린이);

		repository.upsert("업로더-갱신", "v2.pdf", "k2", List.of("public"), "bge-m3", 나중_올린이);

		assertThat(jpaRepository.findByDocKey("업로더-갱신").orElseThrow().getUploadedBy())
				.as("파일을 새로 올린 사람이 현재 문서의 책임자다")
				.isEqualTo(나중_올린이);
	}

	@Test
	@DisplayName("교체 시각이 갱신된다")
	void 교체_시각이_갱신된다() throws InterruptedException {
		repository.upsert("시각-추적", "v1.pdf", "key-a", List.of("public"), "bge-m3", null);
		DocumentEntity 처음 = jpaRepository.findByDocKey("시각-추적").orElseThrow();

		Thread.sleep(10);
		repository.upsert("시각-추적", "v2.pdf", "key-b", List.of("public"), "bge-m3", null);
		DocumentEntity 나중 = jpaRepository.findByDocKey("시각-추적").orElseThrow();

		assertThat(나중.getUpdatedAt()).isAfter(처음.getUpdatedAt());
		assertThat(나중.getCreatedAt()).as("생성 시각은 교체로 바뀌지 않는다").isEqualTo(처음.getCreatedAt());
	}
}
