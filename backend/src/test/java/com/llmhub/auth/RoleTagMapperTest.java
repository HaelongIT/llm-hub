package com.llmhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-AUTH 시나리오: USER 역할 → {public}, ADMIN 역할 → {public, restricted}.
 *
 * <p>S3: 태그 기반 필터 + 역할→태그 매핑 분리. 사용자 태그와 문서 태그는 <b>단일 공유 어휘</b>다. 별도 변환 계층을
 * 만들지 않는다.
 *
 * <p>E4: 매핑은 단일 컴포넌트다. 교체해도 재색인이 필요 없고 다른 코드가 바뀌지 않는다.
 */
class RoleTagMapperTest {

	/** v0 매핑. 설정값이며 코드에 하드코딩하지 않는다 (REL-4). */
	private static final Map<String, Set<String>> v0_매핑 =
			Map.of("USER", Set.of("public"), "ADMIN", Set.of("public", "restricted"));

	private final RoleTagMapper mapper = new ConfiguredRoleTagMapper(v0_매핑);

	@Test
	@DisplayName("USER 역할은 public 태그를 얻는다")
	void USER는_public을_얻는다() {
		assertThat(mapper.tagsFor(Set.of("USER"))).containsExactly("public");
	}

	@Test
	@DisplayName("ADMIN 역할은 public과 restricted를 얻는다")
	void ADMIN은_restricted까지_얻는다() {
		assertThat(mapper.tagsFor(Set.of("ADMIN"))).containsExactlyInAnyOrder("public", "restricted");
	}

	@Test
	@DisplayName("여러 역할을 가지면 태그는 합집합이다")
	void 여러_역할의_태그는_합집합이다() {
		assertThat(mapper.tagsFor(Set.of("USER", "ADMIN"))).containsExactlyInAnyOrder("public", "restricted");
	}

	@Test
	@DisplayName("매핑에 없는 역할은 아무 태그도 주지 않는다")
	void 알_수_없는_역할은_태그를_주지_않는다() {
		assertThat(mapper.tagsFor(Set.of("GUEST")))
				.as("모르는 역할이 조용히 public을 얻으면 권한 모델이 무너진다")
				.isEmpty();
	}

	@Test
	@DisplayName("역할이 없으면 태그도 없다")
	void 역할이_없으면_태그도_없다() {
		assertThat(mapper.tagsFor(Set.of())).isEmpty();
	}

	@Test
	@DisplayName("반환된 태그 집합은 수정할 수 없다")
	void 반환된_태그는_불변이다() {
		Set<String> tags = mapper.tagsFor(Set.of("USER"));

		assertThatThrownBy(() -> tags.add("restricted"))
				.as("하위 계층이 태그를 덧붙일 수 있으면 앞단 게이트가 무의미해진다 (S4)")
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("매핑을 바꾸면 다른 코드 변경 없이 태그 결과만 바뀐다")
	void 매핑_교체가_결과만_바꾼다() {
		RoleTagMapper 부서_매핑 =
				new ConfiguredRoleTagMapper(
						Map.of("USER", Set.of("public", "hr"), "ADMIN", Set.of("public", "hr", "restricted")));

		assertThat(부서_매핑.tagsFor(Set.of("USER")))
				.as("E4: 매핑 교체는 이 컴포넌트 안에서 끝난다. 재색인도 필요 없다")
				.containsExactlyInAnyOrder("public", "hr");
	}

	@Test
	@DisplayName("v0 태그 어휘는 문서 태그와 같은 문자열이다")
	void 태그_어휘가_문서와_동일하다() {
		assertThat(mapper.tagsFor(Set.of("ADMIN")))
				.as("사용자 태그와 문서 태그는 단일 공유 어휘다. 변환 계층을 만들고 싶어지면 그게 위반 신호다 (S3, S18)")
				.containsExactlyInAnyOrder("public", "restricted");
	}
}
