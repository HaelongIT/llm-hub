/**
 * 컨테이너 헬스체크 전용 (REL-5). 오케스트레이터가 BFF의 기동 완료를 판정한다.
 *
 * 아무 상태도 노출하지 않는다. 코어·Keycloak의 상태를 여기서 확인하면, 그것들이 잠깐 흔들릴 때
 * BFF 컨테이너가 재시작되어 사용자 세션까지 끊긴다. 각 컨테이너는 자기 자신만 보고한다.
 */
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export function GET() {
	return new Response('ok', { status: 200, headers: { 'Cache-Control': 'no-store' } });
}
