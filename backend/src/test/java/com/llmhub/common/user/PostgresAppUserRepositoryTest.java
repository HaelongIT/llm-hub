package com.llmhub.common.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.support.PostgresInitializer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * {@code ensureExists}는 "찾고 없으면 삽입"이다. 같은 사용자의 첫 요청이 동시에 여러 개 들어오면 전부 "없다"고
 * 판단하고 전부 삽입한다 — unique 제약 위반이다.
 *
 * <p>단일 요청 테스트로는 절대 드러나지 않는다. 부하 테스트가 실제로 이 버그를 잡았다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PostgresInitializer.class)
class PostgresAppUserRepositoryTest {

	private static final int 동시_요청 = 24;

	@Autowired private AppUserRepository repository;

	@Test
	@DisplayName("같은 사용자의 첫 요청이 동시에 와도 하나의 사용자만 만들어진다")
	void 동시_최초_요청이_경합하지_않는다() throws Exception {
		String subject = "keycloak-" + UUID.randomUUID();

		try (ExecutorService pool = Executors.newFixedThreadPool(동시_요청)) {
			List<Callable<UUID>> 작업 =
					IntStream.range(0, 동시_요청).<Callable<UUID>>mapToObj(i -> () -> repository.ensureExists(subject)).toList();

			List<Future<UUID>> 결과 = pool.invokeAll(작업);

			List<UUID> ids = 결과.stream().map(PostgresAppUserRepositoryTest::값).toList();

			assertThat(ids)
					.as("모두 성공하고 같은 사용자를 가리켜야 한다. 하나라도 실패하면 그 요청은 500이 된다")
					.hasSize(동시_요청)
					.doesNotContainNull()
					.containsOnly(ids.get(0));
		}
	}

	@Test
	@DisplayName("이미 있는 사용자는 같은 id를 돌려준다")
	void 기존_사용자는_같은_id다() {
		String subject = "keycloak-" + UUID.randomUUID();

		UUID 처음 = repository.ensureExists(subject);
		UUID 다시 = repository.ensureExists(subject);

		assertThat(다시).isEqualTo(처음);
	}

	private static UUID 값(Future<UUID> future) {
		try {
			return future.get();
		} catch (Exception e) {
			throw new IllegalStateException("동시 ensureExists 실패 — 경합이 처리되지 않았다", e);
		}
	}
}
