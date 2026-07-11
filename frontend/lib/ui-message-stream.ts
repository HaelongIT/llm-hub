/**
 * 코어의 이벤트 타입 SSE를 AI SDK의 UI Message Stream 파트로 번역한다.
 *
 * 왜 필요한가: `useChat`은 SSE의 `event:` 이름을 읽지 않는다. `data:` 안 JSON의 `type` 필드로
 * 파트를 구분한다. 코어는 S6가 요구하는 이벤트 타입 SSE(`text`/`sources`/`error`/`done`)를
 * 그대로 유지하고, 번역은 BFF 한 곳에만 존재한다 (docs/01).
 *
 * 근거는 내장 `source-document` 파트에 담기엔 좁다(위치·점수를 못 싣는다). 커스텀 `data-sources`
 * 파트를 쓴다.
 */

/** 코어가 보내는 SSE 프레임. */
export type CoreEvent = {
	event: 'sources' | 'text' | 'error' | 'done';
	data: string;
};

/** AI SDK UI Message Stream 파트. */
export type UiPart = Record<string, unknown>;

/** 응답에 반드시 붙어야 하는 헤더. 없으면 useChat이 스트림을 인식하지 못한다. */
export const UI_MESSAGE_STREAM_HEADERS = {
	'Content-Type': 'text/event-stream',
	'Cache-Control': 'no-cache',
	Connection: 'keep-alive',
	'x-vercel-ai-ui-message-stream': 'v1',
} as const;

/**
 * 코어의 SSE 프레임 문자열을 하나씩 잘라낸다.
 *
 * Spring의 `ServerSentEvent`는 `event:이름\ndata:내용\n\n` 형태로 보낸다. 여러 줄 data도
 * 있을 수 있으므로 줄 단위로 모은다.
 */
export function parseSseFrame(frame: string): CoreEvent | null {
	let event = '';
	const dataLines: string[] = [];

	for (const line of frame.split('\n')) {
		if (line.startsWith('event:')) {
			event = line.slice('event:'.length).trim();
		} else if (line.startsWith('data:')) {
			dataLines.push(line.slice('data:'.length).trimStart());
		}
	}

	if (!event) return null;
	return { event: event as CoreEvent['event'], data: dataLines.join('\n') };
}

/**
 * 번역 상태. 텍스트 블록은 한 번만 열고 한 번만 닫는다.
 *
 * 파트 사이에 상태가 필요하므로 순수 함수가 아니라 작은 상태 기계다. 하지만 I/O가 없어 테스트가 쉽다.
 */
export class UiMessageStreamTranslator {
	private started = false;
	private textOpen = false;
	private closed = false;
	private readonly messageId: string;

	// 생성자 파라미터 프로퍼티는 쓰지 않는다. Node의 타입 스트리핑은 코드를 생성하지 않으므로
	// 그 문법을 지원하지 않는다 (ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX).
	constructor(messageId: string) {
		this.messageId = messageId;
	}

	/** 코어 이벤트 하나를 0개 이상의 AI SDK 파트로 옮긴다. */
	translate(event: CoreEvent): UiPart[] {
		if (this.closed) return [];

		const parts: UiPart[] = [];
		if (!this.started) {
			this.started = true;
			parts.push({ type: 'start', messageId: this.messageId });
		}

		switch (event.event) {
			case 'sources':
				// 근거는 첫 토큰보다 먼저 온다. 그 순서를 그대로 보존한다 (S6).
				parts.push({ type: 'data-sources', data: JSON.parse(event.data) });
				break;

			case 'text':
				if (!this.textOpen) {
					this.textOpen = true;
					parts.push({ type: 'text-start', id: this.messageId });
				}
				parts.push({ type: 'text-delta', id: this.messageId, delta: event.data });
				break;

			case 'done':
				parts.push(...this.closeText());
				parts.push({ type: 'finish' });
				this.closed = true;
				break;

			case 'error':
				// 부분 응답 후 끊김을 사용자가 구분할 수 있어야 한다 (REL-1).
				parts.push(...this.closeText());
				parts.push({ type: 'error', errorText: event.data });
				this.closed = true;
				break;
		}

		return parts;
	}

	/** 코어가 done도 error도 없이 스트림을 끊은 경우. 열린 텍스트 블록을 닫는다. */
	finish(): UiPart[] {
		if (this.closed) return [];
		this.closed = true;
		return [...this.closeText(), { type: 'error', errorText: '스트림이 예기치 않게 종료되었습니다.' }];
	}

	private closeText(): UiPart[] {
		if (!this.textOpen) return [];
		this.textOpen = false;
		return [{ type: 'text-end', id: this.messageId }];
	}
}

/** AI SDK는 `data: <JSON>` 줄로 파트를 읽고, `data: [DONE]`으로 스트림 끝을 안다. */
export function toSseLine(part: UiPart): string {
	return `data: ${JSON.stringify(part)}\n\n`;
}

export const DONE_LINE = 'data: [DONE]\n\n';

/**
 * 코어의 SSE 바이트 스트림을 useChat이 읽을 UI Message Stream 바이트 스트림으로 번역한다.
 *
 * <b>라우트와 테스트가 이 한 함수를 공유해야 한다 (R-14).</b> 라우트에만 두고 테스트가 복제하면, 읽기 루프의
 * try/catch(업스트림이 끊기면 고정 문구로 닫는다 — SEC-3) 같은 분기가 복제본에서 빠져도 아무도 잡지 못한다.
 *
 * @param messageId 기본은 무작위. 테스트는 결정성을 위해 고정값을 넘긴다.
 */
export function translateCoreStream(
	upstream: ReadableStream<Uint8Array>,
	messageId: string = crypto.randomUUID(),
): ReadableStream<Uint8Array> {
	const decoder = new TextDecoder();
	const encoder = new TextEncoder();
	const translator = new UiMessageStreamTranslator(messageId);
	let buffer = '';
	const reader = upstream.getReader();

	return new ReadableStream({
		async start(controller) {
			const emit = (line: string) => controller.enqueue(encoder.encode(line));

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
				// 스트림 끝에 남은 마지막 프레임을 파싱한다. 코어가 마지막 프레임을 `\n\n` 없이 끝내면 buffer에
				// 남아 사라진다 — 그러면 done을 못 보고 finish()가 오류로 닫는다 (L-7).
				const leftover = parseSseFrame(buffer);
				if (leftover) {
					for (const part of translator.translate(leftover)) {
						emit(toSseLine(part));
					}
				}
				// 코어가 done/error 없이 끊었다면 error로 닫는다. 무한 대기를 만들지 않는다 (REL-1).
				for (const part of translator.finish()) {
					emit(toSseLine(part));
				}
			} catch {
				// 내부 예외 문구를 브라우저로 흘리지 않는다 (SEC-3). 원인은 서버 로그에 있다.
				for (const part of translator.translate({ event: 'error', data: '요청을 처리하지 못했습니다.' })) {
					emit(toSseLine(part));
				}
			} finally {
				emit(DONE_LINE);
				controller.close();
			}
		},
		cancel(reason) {
			// 클라이언트가 끊으면 코어→LLM 스트림도 끊는다. 버려진 스트림이 LLM 토큰 생성과 커넥션을
			// 계속 낭비하지 않게 한다 (R-17). 코어는 이 취소를 받아 Flux를 취소하고 감사에 CANCELLED로 남긴다.
			return reader.cancel(reason);
		},
	});
}
