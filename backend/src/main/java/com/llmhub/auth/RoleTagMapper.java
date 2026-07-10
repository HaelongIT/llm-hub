package com.llmhub.auth;

import java.util.Set;

/**
 * Keycloak 역할을 접근 태그로 바꾼다. <b>단일 지점</b>이다 (E4).
 *
 * <p>이 컴포넌트를 교체하면 태그 결과만 바뀐다. 재색인도, 다른 코드 변경도 필요 없다. 부서·그룹 기반 매핑이나 계층형 역할은
 * 이 구현을 갈아끼워 수용한다.
 *
 * <p>산출되는 태그는 document의 {@code access_tags}와 <b>같은 어휘</b>다 (S3, S18). 두 태그 체계를
 * 따로 만들지 않는다. 변환 계층을 만들고 싶어지면 그것이 위반 신호다.
 */
public interface RoleTagMapper {

	/**
	 * @param roles 요청 주체의 역할 집합
	 * @return 접근 태그 집합. 수정 불가능하다. 모르는 역할은 아무 태그도 기여하지 않는다.
	 */
	Set<String> tagsFor(Set<String> roles);
}
