import { proxyToCore } from '@/lib/core';

export const runtime = 'nodejs';

export async function DELETE(_request: Request, context: { params: Promise<{ sessionId: string }> }) {
	const { sessionId } = await context.params;
	// 남의 세션은 코어가 404로 답한다. 그 응답을 그대로 전달한다.
	return proxyToCore(`/api/sessions/${sessionId}`, { method: 'DELETE' });
}
