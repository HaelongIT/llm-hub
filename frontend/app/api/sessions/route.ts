import { proxyToCore } from '@/lib/core';

/** 세션은 사용자 소유다. 코어가 토큰의 subject로 소유자를 판단한다 (S2). */
export const runtime = 'nodejs';

export async function GET() {
	return proxyToCore('/api/sessions');
}

export async function POST(request: Request) {
	const body = await request.text();
	return proxyToCore('/api/sessions', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: body || '{}',
	});
}
