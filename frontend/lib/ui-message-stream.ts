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
