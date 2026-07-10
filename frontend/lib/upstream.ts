/**
 * 코어 응답을 클라이언트로 중계할 때의 규칙. 순수 함수라 테스트에서 켤 수 있다.
 *
 * `core.ts`는 `@/auth`를 끌고 오므로 여기에 둔다.
 */

/**
 * 코어의 상태 코드를 <b>뭉개지 않는다.</b>
 *
 * 코어는 남의 세션에 404(403이 아니다 — 403은 그 세션이 존재한다는 사실을 알려준다),
 * 너무 긴 질문에 400, 만료된 토큰에 401을 준다. 전부 502로 바꾸면 클라이언트는 셋을
 * 구분할 수 없고, 401을 못 보므로 재로그인을 유도할 수도 없다.
 *
 * 502는 <b>상태가 성공인데 본문이 없을 때만</b>이다. 그것이 진짜 게이트웨이 오류다.
 */
export function relayFailure(upstream: Response): Response {
	if (upstream.ok) {
		return new Response('코어가 본문 없이 응답했습니다.', { status: 502 });
	}
	return new Response(upstream.body, {
		status: upstream.status,
		headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'text/plain; charset=utf-8' },
	});
}
