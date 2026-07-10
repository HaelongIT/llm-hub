import { auth } from '@/auth';
import {
	DONE_LINE,
	parseSseFrame,
	toSseLine,
	UiMessageStreamTranslator,
	UI_MESSAGE_STREAM_HEADERS,
} from '@/lib/ui-message-stream';

/**
 * BFF. 코어의 이벤트 타입 SSE를 AI SDK의 UI Message Stream으로 번역한다 (docs/01).
 *
 * 코어는 S6가 요구하는 명명된 이벤트를 그대로 내보내고, 번역은 여기 한 곳에만 존재한다.
 * 클라이언트는 LiteLLM 게이트웨이나 코어에 직접 접근하지 않는다 (SEC-1).
 */
export const runtime = 'nodejs';

const CORE_URL = process.env.CORE_BASE_URL ?? 'http://localhost:8080';

export async function POST(request: Request) {
	const session = await auth();
	if (!session?.accessToken) {
		return new Response('Unauthorized', { status: 401 });
	}

	const body = await request.json();

	const upstream = await fetch(`${CORE_URL}/api/chat/stream`, {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json',
			// 세션 쿠키를 Authorization 헤더로 바꾼다 (docs/01 CLIENT).
			Authorization: `Bearer ${session.accessToken}`,
		},
		body: JSON.stringify({
			sessionId: body.sessionId ?? null,
			question: lastUserText(body.messages),
		}),
	});

	if (!upstream.ok || !upstream.body) {
		return new Response(`코어 응답 실패: ${upstream.status}`, { status: 502 });
	}

	return new Response(translate(upstream.body), { headers: UI_MESSAGE_STREAM_HEADERS });
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

function translate(upstream: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
	const decoder = new TextDecoder();
	const encoder = new TextEncoder();
	const translator = new UiMessageStreamTranslator(crypto.randomUUID());
	let buffer = '';

	return new ReadableStream({
		async start(controller) {
			const emit = (line: string) => controller.enqueue(encoder.encode(line));
			const reader = upstream.getReader();

			try {
				for (;;) {
					const { done, value } = await reader.read();
					if (done) break;

					buffer += decoder.decode(value, { stream: true });

					// SSE 프레임은 빈 줄로 구분된다. 마지막 조각은 다음 청크와 이어질 수 있으므로 남긴다.
					const frames = buffer.split('\n\n');
					buffer = frames.pop() ?? '';

					for (const frame of frames) {
						const event = parseSseFrame(frame);
						if (!event) continue;
						for (const part of translator.translate(event)) {
							emit(toSseLine(part));
						}
					}
				}
				// 코어가 done/error 없이 끊었다면 error로 닫는다. 무한 대기를 만들지 않는다 (REL-1).
				for (const part of translator.finish()) {
					emit(toSseLine(part));
				}
			} catch (error) {
				for (const part of translator.translate({ event: 'error', data: String(error) })) {
					emit(toSseLine(part));
				}
			} finally {
				emit(DONE_LINE);
				controller.close();
			}
		},
	});
}
