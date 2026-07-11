#!/usr/bin/env bash
#
# 운영 Keycloak 프로비저닝. 운영 스택을 처음 띄운 뒤 한 번 실행한다 (배포 호스트에서):
#
#   docker compose -f docker-compose.yml up -d
#   ./docker/keycloak/bootstrap-prod.sh
#
# 무엇을 하는가 — realm-export.json이 --import-realm로 만들지 못하는 "배포별" 값만 얹는다:
#   - 프론트 클라이언트(llmhub-frontend) 시크릿          ← .env의 KEYCLOAK_CLIENT_SECRET
#   - redirect URI / web origin                          ← .env의 AUTH_URL(없으면 FRONTEND_ORIGIN)
# 이 둘은 저장소에 커밋할 수 없다(SEC-3, 호스트마다 다름). 그래서 import가 아니라 이 스크립트다.
#
# 무엇을 하지 않는가:
#   - realm·역할·llmhub-backend·llmhub-frontend·audience 매퍼는 realm-export.json이 이미 만든다.
#   - directAccessGrants를 켜지 않는다(운영은 password grant 미사용). 명시적으로 false로 굳힌다.
#   - 실 사용자를 만들지 않는다(회사 IdP/관리자 몫). dev-user/dev-admin은 dev 전용이다.
#
# 마지막에 audience 매퍼 존재를 assert한다 — R-16이 aud=llmhub-backend를 강제하므로, 매퍼가 없으면
# 정당한 로그인도 백엔드가 401로 막는다(fail-closed 자기 DoS). import drift를 여기서 크게 실패시킨다.
#
# 재실행 가능하다. 시크릿/redirect가 바뀌면 다시 돌리면 된다.

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

# redirect/web origin은 프론트의 공개 주소다. next-auth는 AUTH_URL을 canonical로 쓴다.
# AUTH_URL이 없으면 FRONTEND_ORIGIN으로 떨어진다. 둘 다 없으면 멈춘다.
origin="${AUTH_URL:-${FRONTEND_ORIGIN:-}}"
if [ -z "$origin" ]; then
	echo "✗ .env에 AUTH_URL(또는 FRONTEND_ORIGIN)이 필요하다 — 프론트 공개 주소." >&2
	exit 1
fi

# kcadm은 keycloak 컨테이너 안에서 돈다. 운영 파일만 명시한다 — override(dev 완화)가 얹히면 안 된다.
# Keycloak은 컨테이너 내부에서 8080을 듣는다.
#
# MSYS_NO_PATHCONV: Git Bash(Windows)가 `/opt/keycloak/...`를 Windows 경로로 바꾸는 것을 막는다.
kcadm() {
	MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
		docker compose -f docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

echo "▸ 관리자로 로그인"
kcadm config credentials \
	--server http://localhost:8080 \
	--realm master \
	--user "$KEYCLOAK_ADMIN" \
	--password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# ─────────────────────────────────────────────────────────────
# 프론트 클라이언트를 찾는다. realm-export.json import가 만들어 두었어야 한다.
# ─────────────────────────────────────────────────────────────
client_id=$(kcadm get clients -r "$KEYCLOAK_REALM" \
	-q "clientId=$KEYCLOAK_CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r')

if [ -z "$client_id" ]; then
	echo "✗ 클라이언트 '$KEYCLOAK_CLIENT_ID'가 realm '$KEYCLOAK_REALM'에 없다." >&2
	echo "  realm import가 실행됐는지 확인하라: docker compose -f docker-compose.yml up -d" >&2
	echo "  (Keycloak은 최초 기동 때만 --import-realm을 적용한다.)" >&2
	exit 1
fi

# ─────────────────────────────────────────────────────────────
# 배포별 값만 갱신한다. directAccessGrants는 명시적으로 false로 굳힌다(운영 하드닝).
# ─────────────────────────────────────────────────────────────
echo "▸ 클라이언트 갱신: $KEYCLOAK_CLIENT_ID (secret, redirect=$origin)"
kcadm update "clients/$client_id" -r "$KEYCLOAK_REALM" \
	-s directAccessGrantsEnabled=false \
	-s "secret=$KEYCLOAK_CLIENT_SECRET" \
	-s "redirectUris=[\"$origin/*\"]" \
	-s "webOrigins=[\"$origin\"]" >/dev/null

# ─────────────────────────────────────────────────────────────
# audience 매퍼 존재를 assert한다. 없으면 R-16이 정당한 토큰도 401로 막는다 → 크게 실패시킨다.
# ─────────────────────────────────────────────────────────────
has_audience_mapper=$(kcadm get "clients/$client_id/protocol-mappers/models" -r "$KEYCLOAK_REALM" \
	--fields name --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -c "^llmhub-backend-audience$" || true)

if [ "$has_audience_mapper" = "0" ]; then
	echo "✗ audience 매퍼 'llmhub-backend-audience'가 없다." >&2
	echo "  realm-export.json의 llmhub-frontend.protocolMappers를 확인하라 — 없으면 R-16이" >&2
	echo "  aud=llmhub-backend를 강제하므로 모든 로그인이 백엔드에서 401로 막힌다." >&2
	exit 1
fi

echo
echo "완료. 운영 프론트 클라이언트가 프로비저닝됐다:"
echo "  clientId       : $KEYCLOAK_CLIENT_ID"
echo "  redirect/origin: $origin"
echo "  audience 매퍼   : llmhub-backend-audience → aud=llmhub-backend (확인됨)"
echo
echo "실 사용자는 이 스크립트가 만들지 않는다 — 회사 IdP 연동 또는 관리자가 만든다."
