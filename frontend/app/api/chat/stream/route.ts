import { bearerToken } from '@/lib/core';
import { hasUsableToken } from '@/lib/token';
import { relayFailure } from '@/lib/upstream';
import { translateCoreStream, UI_MESSAGE_STREAM_HEADERS } from '@/lib/ui-message-stream';

/**
 * BFF. 코어의 이벤트 타입 SSE를 AI SDK의 UI Message Stream으로 번역한다 (docs/01).
 *
 * 코어는 S6가 요구하는 명명된 이벤트를 그대로 내보내고, 번역은 여기 한 곳에만 존재한다.
 * 클라이언트는 LiteLLM 게이트웨이나 코어에 직접 접근하지 않는다 (SEC-1).
 */
export const runtime = 'nodejs';

const CORE_URL = process.env.CORE_BASE_URL ?? 'http://localhost:8080';

export async function POST(request: Request) {
	// 토큰은 세션이 아니라 암호화된 JWT 쿠키에서 서버측으로 읽는다 (R-8). 갱신 실패 세션은
	// 만료된 베어러를 들고 있으므로 상류로 보내지 않는다 (S25).
	const token = await bearerToken();
	if (!hasUsableToken(token)) {
		return new Response('Unauthorized', { status: 401 });
	}
	const { accessToken } = token;

	const body = await request.json();

	const upstream = await fetch(`${CORE_URL}/api/chat/stream`, {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json',
			// 세션 쿠키를 Authorization 헤더로 바꾼다 (docs/01 CLIENT).
			Authorization: `Bearer ${accessToken}`,
		},
		body: JSON.stringify({
			sessionId: body.sessionId ?? null,
			question: lastUserText(body.messages),
		}),
		// 클라이언트가 끊으면 코어 호출도 끊는다. 코어는 이 취소로 LLM 스트림을 멈춘다 (R-17).
		signal: request.signal,
	});

	// 코어의 상태를 뭉개지 않는다. 404(남의 세션) · 400(질문 길이) · 401(만료)을 클라이언트가
	// 구분할 수 있어야 하고, 401을 봐야 재로그인을 유도할 수 있다.
	if (!upstream.ok || !upstream.body) {
		return relayFailure(upstream);
	}

	return new Response(translateCoreStream(upstream.body), { headers: UI_MESSAGE_STREAM_HEADERS });
}

/** 검색 쿼리는 마지막 사용자 질문 그대로다 (S2). */
function lastUserText(messages: Array<{ role: string; parts?: Array<{ type: string; text?: string }> }>): string {
	const lastUser = [...messages].reverse().find((m) => m.role === 'user');
	return (
		lastUser?.parts
			?.filter((p) => p.type === 'text')
			.map((p) => p.text ?? '')
			.join('') ?? ''
	);
}

