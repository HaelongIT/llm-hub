package com.llmhub.common.user;

import java.util.UUID;

/** 로컬 사용자 식별. 인증은 Keycloak이 하고 여기엔 참조용 최소 정보만 둔다 (docs/03). */
public interface AppUserRepository {

	/** Keycloak subject로 사용자를 찾거나 만든다. 역할은 저장하지 않는다 — 이중 관리가 되기 때문이다. */
	UUID ensureExists(String keycloakSubject);
}
