import { headers } from 'next/headers';
import { getToken } from 'next-auth/jwt';

import type { TokenSet } from '@/lib/token';

/**
 * BFF에서 코어를 부른다. 세션 쿠키를 `Authorization` 헤더로 바꾼다 (docs/01).
 *
 * 클라이언트는 코어에 직접 접근하지 않는다. 코어·Elasticsearch·PostgreSQL·LiteLLM은
 * 내부망 전용이다 (SEC-1).
 */
const CORE_URL = process.env.CORE_BASE_URL ?? 'http://localhost:8080';

/**
 * access token을 <b>암호화된 JWT 쿠키에서 서버측으로</b> 읽는다 (R-8).
 *
 * 세션(`auth()`의 반환)에 토큰을 실으면 `/api/auth/session`으로 브라우저 JS에 노출된다 — "BFF만
 * 토큰을 쥔다"(docs/01)가 깨지고, XSS 한 번에 탈취된다. 토큰은 JWT에만 두고 여기서만 꺼낸다.
 *
 * salt는 쿠키명과 같아야 복호화된다(Auth.js 규약). 쿠키명은 https에서 `__Secure-` 접두사가 붙으므로,
 * 실제 요청 쿠키에 그 접두사가 있는지로 감지해 dev(http)·운영(https) 모두 맞춘다. 청크된 쿠키는
 * getToken 내부의 SessionStore가 다시 합친다.
 */
export async function bearerToken(): Promise<{ accessToken?: string; error?: string }> {
	const requestHeaders = await headers();
	const secureCookie = (requestHeaders.get('cookie') ?? '').includes('__Secure-authjs.session-token');

	const token = (await getToken({
		req: { headers: requestHeaders },
		secret: process.env.AUTH_SECRET,
		secureCookie,
	})) as (TokenSet & Record<string, unknown>) | null;

	return { accessToken: token?.accessToken, error: token?.error };
}

/**
 * 인증되지 않았거나 토큰 갱신에 실패했으면 401. 코어까지 가지 않는다.
 *
 * 갱신에 실패한 세션은 만료된 베어러를 들고 있다. 그것을 상류로 보내면 코어가 401을 줄 뿐이고,
 * 사용자는 원인을 알 수 없다. 여기서 끊는다 (S25).
 */
export async function proxyToCore(path: string, init: RequestInit = {}): Promise<Response> {
	const { accessToken, error } = await bearerToken();
	if (!accessToken || error) {
		return new Response('Unauthorized', { status: 401 });
	}

	const upstream = await fetch(`${CORE_URL}${path}`, {
		...init,
		headers: {
			...init.headers,
			Authorization: `Bearer ${accessToken}`,
		},
	});

	// 코어의 상태 코드를 그대로 전달한다. 남의 세션은 404다 — 403은 그 세션이 있다는
	// 사실을 알려주므로 코어가 404로 답하고, BFF는 그것을 바꾸지 않는다.
	return new Response(upstream.body, {
		status: upstream.status,
		headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json' },
	});
}
