#!/usr/bin/env bash
#
# 로컬 개발용 Keycloak 부트스트랩. 저장소 루트에서 실행한다:
#
#   ./docker/keycloak/bootstrap-dev.sh
#
# realm(llmhub)과 역할(USER/ADMIN)은 realm-export.json이 만든다. 이 스크립트는
# 거기에 담을 수 없는 것들을 만든다 — 비밀번호와 클라이언트 시크릿이다.
# 저장소에 커밋되면 안 되므로(SEC-3) .env에서 읽는다.
#
# 재실행 가능하다. 이미 있는 것은 갱신하고 없는 것은 만든다.
#
# 운영에는 쓰지 않는다. 개발용 사용자와 direct access grant는 로컬 전용이다.

set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"

if [ -f .env ]; then
	set -a
	# shellcheck disable=SC1091
	. ./.env
	set +a
fi

: "${KEYCLOAK_REALM:=llmhub}"
: "${KEYCLOAK_ADMIN:?.env에 KEYCLOAK_ADMIN이 필요하다}"
: "${KEYCLOAK_ADMIN_PASSWORD:?.env에 KEYCLOAK_ADMIN_PASSWORD가 필요하다}"
: "${KEYCLOAK_CLIENT_ID:=llmhub-frontend}"
: "${KEYCLOAK_CLIENT_SECRET:?.env에 KEYCLOAK_CLIENT_SECRET이 필요하다}"
: "${FRONTEND_ORIGIN:=http://localhost:3000}"
: "${DEV_USER_PASSWORD:?.env에 DEV_USER_PASSWORD가 필요하다}"
: "${DEV_ADMIN_PASSWORD:?.env에 DEV_ADMIN_PASSWORD가 필요하다}"

# kcadm은 컨테이너 안에서 돈다. Keycloak은 컨테이너 내부에서 8080을 듣는다.
#
# MSYS_NO_PATHCONV: Git Bash(Windows)는 `/opt/keycloak/...` 같은 인자를 Windows 경로로
# 바꿔버린다. 다른 OS에서는 그냥 무시되는 환경변수다.
kcadm() {
	MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
		docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

echo "▸ 관리자로 로그인"
kcadm config credentials \
	--server http://localhost:8080 \
	--realm master \
	--user "$KEYCLOAK_ADMIN" \
	--password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# ─────────────────────────────────────────────────────────────
# 프론트엔드 클라이언트. next-auth(Auth.js)가 Authorization Code Flow로 쓴다.
# 클라이언트 자체(구조)와 audience 매퍼는 이제 realm-export.json이 --import-realm로 만든다
# (dev·운영 공통, 선언적). 여기서는 realm-export에 넣을 수 없는 배포별 값만 얹는다:
#   - 시크릿 (커밋 금지, SEC-3)
#   - redirect URI / web origin (배포별 호스트)
#   - directAccessGrantsEnabled=true — dev 전용. 스모크 테스트가 password grant로 토큰을 받는다.
#     realm-export의 기본은 false(안전)이므로 dev에서만 켠다. 운영(bootstrap-prod.sh)은 켜지 않는다.
# 클라이언트는 import로 이미 존재하므로 항상 update 경로를 탄다. 그래도 재실행 안전하게 생성도 남긴다.
# ─────────────────────────────────────────────────────────────
client_id=$(kcadm get clients -r "$KEYCLOAK_REALM" \
	-q "clientId=$KEYCLOAK_CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r')

if [ -z "$client_id" ]; then
	echo "▸ 클라이언트 생성: $KEYCLOAK_CLIENT_ID (realm import가 안 돌았다 — fallback)"
	kcadm create clients -r "$KEYCLOAK_REALM" \
		-s "clientId=$KEYCLOAK_CLIENT_ID" \
		-s enabled=true \
		-s publicClient=false \
		-s standardFlowEnabled=true \
		-s directAccessGrantsEnabled=true \
		-s "secret=$KEYCLOAK_CLIENT_SECRET" \
		-s "redirectUris=[\"$FRONTEND_ORIGIN/*\"]" \
		-s "webOrigins=[\"$FRONTEND_ORIGIN\"]" >/dev/null
else
	echo "▸ 클라이언트 갱신: $KEYCLOAK_CLIENT_ID"
	kcadm update "clients/$client_id" -r "$KEYCLOAK_REALM" \
		-s directAccessGrantsEnabled=true \
		-s "secret=$KEYCLOAK_CLIENT_SECRET" \
		-s "redirectUris=[\"$FRONTEND_ORIGIN/*\"]" \
		-s "webOrigins=[\"$FRONTEND_ORIGIN\"]" >/dev/null
fi

# ─────────────────────────────────────────────────────────────
# audience 매퍼. 백엔드가 aud=llmhub-backend를 검증하므로(R-16), 이 클라이언트가 발급하는 access
# token에 그 대상을 넣어 준다. 이제 realm-export.json이 매퍼를 선언적으로 만들므로, 아래는 재실행/
# fallback 안전장치일 뿐이다 — import가 이미 만든 경우 조용히 건너뛴다.
# ─────────────────────────────────────────────────────────────
client_id=$(kcadm get clients -r "$KEYCLOAK_REALM" \
	-q "clientId=$KEYCLOAK_CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r')

has_audience_mapper=$(kcadm get "clients/$client_id/protocol-mappers/models" -r "$KEYCLOAK_REALM" \
	--fields name --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -c "^llmhub-backend-audience$" || true)

if [ "$has_audience_mapper" = "0" ]; then
	echo "▸ audience 매퍼 추가: aud=llmhub-backend (import에 없었다 — fallback)"
	kcadm create "clients/$client_id/protocol-mappers/models" -r "$KEYCLOAK_REALM" \
		-s name=llmhub-backend-audience \
		-s protocol=openid-connect \
		-s protocolMapper=oidc-audience-mapper \
		-s 'config."included.client.audience"=llmhub-backend' \
		-s 'config."id.token.claim"=false' \
		-s 'config."access.token.claim"=true' >/dev/null
fi

# ─────────────────────────────────────────────────────────────
# 개발용 사용자. 역할이 접근 태그를 결정한다 (S3):
#   USER  → {public}
#   ADMIN → {public, restricted} + 색인 API 호출 권한
# ─────────────────────────────────────────────────────────────
ensure_user() {
	local username="$1" password="$2" role="$3"

	local user_id
	user_id=$(kcadm get users -r "$KEYCLOAK_REALM" \
		-q "username=$username" -q exact=true --fields id --format csv --noquotes | tr -d '\r')

	# Keycloak 24+의 선언적 사용자 프로필은 email·firstName·lastName을 필수로 본다.
	# 비어 있으면 VERIFY_PROFILE 필수 액션이 걸려 로그인이 "Account is not fully set up"으로
	# 막힌다. 사용자 목록에 requiredActions로 보이지 않아 진단이 어렵다.
	local profile=(
		-s enabled=true
		-s emailVerified=true
		-s "email=$username@example.local"
		-s "firstName=Dev"
		-s "lastName=${username#dev-}"
	)

	if [ -z "$user_id" ]; then
		echo "▸ 사용자 생성: $username ($role)"
		kcadm create users -r "$KEYCLOAK_REALM" -s "username=$username" "${profile[@]}" >/dev/null
	else
		echo "▸ 사용자 갱신: $username ($role)"
		kcadm update "users/$user_id" -r "$KEYCLOAK_REALM" "${profile[@]}" >/dev/null
	fi

	kcadm set-password -r "$KEYCLOAK_REALM" --username "$username" --new-password "$password"
	# add-roles는 이미 있으면 조용히 지나간다.
	kcadm add-roles -r "$KEYCLOAK_REALM" --uusername "$username" --rolename "$role"
}

ensure_user dev-user "$DEV_USER_PASSWORD" USER
ensure_user dev-admin "$DEV_ADMIN_PASSWORD" ADMIN

echo
echo "완료. 개발용 계정:"
echo "  dev-user  (USER)  → 접근 태그 {public}"
echo "  dev-admin (ADMIN) → 접근 태그 {public, restricted}, 색인 API 호출 가능"
echo
echo "비밀번호는 .env의 DEV_USER_PASSWORD / DEV_ADMIN_PASSWORD 다."
