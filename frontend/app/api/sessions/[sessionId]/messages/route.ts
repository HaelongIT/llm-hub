import { proxyToCore } from '@/lib/core';

export const runtime = 'nodejs';

export async function GET(_request: Request, context: { params: Promise<{ sessionId: string }> }) {
	const { sessionId } = await context.params;
	return proxyToCore(`/api/sessions/${sessionId}/messages`);
}
