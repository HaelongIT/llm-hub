import { auth } from '@/auth';

/**
 * BFF에서 코어를 부른다. 세션 쿠키를 `Authorization` 헤더로 바꾼다 (docs/01).
 *
 * 클라이언트는 코어에 직접 접근하지 않는다. 코어·Elasticsearch·PostgreSQL·LiteLLM은
 * 내부망 전용이다 (SEC-1).
 */
const CORE_URL = process.env.CORE_BASE_URL ?? 'http://localhost:8080';

/** 인증되지 않았으면 401. 코어까지 가지 않는다. */
export async function proxyToCore(path: string, init: RequestInit = {}): Promise<Response> {
	const session = await auth();
	if (!session?.accessToken) {
		return new Response('Unauthorized', { status: 401 });
	}

	const upstream = await fetch(`${CORE_URL}${path}`, {
		...init,
		headers: {
			...init.headers,
			Authorization: `Bearer ${session.accessToken}`,
		},
	});

	// 코어의 상태 코드를 그대로 전달한다. 남의 세션은 404다 — 403은 그 세션이 있다는
	// 사실을 알려주므로 코어가 404로 답하고, BFF는 그것을 바꾸지 않는다.
	return new Response(upstream.body, {
		status: upstream.status,
		headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json' },
	});
}
