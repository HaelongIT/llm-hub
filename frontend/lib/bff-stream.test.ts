import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	DONE_LINE,
	parseSseFrame,
	toSseLine,
	UiMessageStreamTranslator,
} from './ui-message-stream.ts';

/**
 * BFF 번역을 실제 스트림 위에서 확인한다.
 *
 * 코어가 보내는 바이트를 그대로 흘려 넣고, useChat이 읽을 줄들이 나오는지 본다. 청크 경계가 SSE 프레임
 * 중간을 자르는 경우가 실제로 일어나므로 그것도 재현한다.
 */

/** BFF 라우트의 변환 로직과 동일하다. 라우트는 이것을 Response로 감싸기만 한다. */
function translate(upstream: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
	const decoder = new TextDecoder();
	const encoder = new TextEncoder();
	const translator = new UiMessageStreamTranslator('msg-1');
	let buffer = '';

	return new ReadableStream({
		async start(controller) {
			const emit = (line: string) => controller.enqueue(encoder.encode(line));
			const reader = upstream.getReader();
			for (;;) {
				const { done, value } = await reader.read();
				if (done) break;
				buffer += decoder.decode(value, { stream: true });
				const frames = buffer.split('\n\n');
				buffer = frames.pop() ?? '';
				for (const frame of frames) {
					const event = parseSseFrame(frame);
					if (!event) continue;
					for (const part of translator.translate(event)) emit(toSseLine(part));
				}
			}
			for (const part of translator.finish()) emit(toSseLine(part));
			emit(DONE_LINE);
			controller.close();
		},
	});
}

function 코어_스트림(chunks: string[]): ReadableStream<Uint8Array> {
	const encoder = new TextEncoder();
	return new ReadableStream({
		start(controller) {
			for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
			controller.close();
		},
	});
}

async function 읽는다(stream: ReadableStream<Uint8Array>): Promise<string> {
	const chunks: string[] = [];
	const decoder = new TextDecoder();
	const reader = stream.getReader();
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		chunks.push(decoder.decode(value, { stream: true }));
	}
	return chunks.join('');
}

function 파트들(wire: string): Array<Record<string, unknown>> {
	return wire
		.split('\n\n')
		.map((line) => line.replace(/^data: /, '').trim())
		.filter((line) => line && line !== '[DONE]')
		.map((line) => JSON.parse(line));
}

describe('BFF 스트림 번역', () => {
	it('정상 흐름이 start → data-sources → text → finish → [DONE]으로 나온다', async () => {
		const 코어 = 코어_스트림([
			'event:sources\ndata:[{"documentId":"doc-1","documentName":"규정.txt","location":"0","text":"연차휴가는 15일","score":1.5}]\n\n',
			'event:text\ndata:연차휴가는 \n\n',
			'event:text\ndata:15일입니다.\n\n',
			'event:done\ndata:trace-1\n\n',
		]);

		const wire = await 읽는다(translate(코어));

		assert.deepEqual(
			파트들(wire).map((p) => p.type),
			['start', 'data-sources', 'text-start', 'text-delta', 'text-delta', 'text-end', 'finish'],
		);
		assert.ok(wire.endsWith(DONE_LINE), '[DONE]으로 끝난다');
	});

	it('청크 경계가 프레임 중간을 잘라도 프레임을 잃지 않는다', async () => {
		const 코어 = 코어_스트림(['event:text\nda', 'ta:연차휴가\n\nevent:do', 'ne\ndata:trace-1\n\n']);

		const parts = 파트들(await 읽는다(translate(코어)));

		assert.deepEqual(
			parts.map((p) => p.type),
			['start', 'text-start', 'text-delta', 'text-end', 'finish'],
		);
		assert.equal(parts[2].delta, '연차휴가');
	});

	it('근거가 첫 토큰보다 먼저 도착한다 (S6)', async () => {
		const 코어 = 코어_스트림([
			'event:sources\ndata:[{"documentId":"doc-1"}]\n\n',
			'event:text\ndata:답\n\n',
			'event:done\ndata:t\n\n',
		]);

		const types = 파트들(await 읽는다(translate(코어))).map((p) => p.type);

		assert.ok(types.indexOf('data-sources') < types.indexOf('text-delta'));
	});

	it('코어의 error 이벤트가 error 파트로 전달된다', async () => {
		const 코어 = 코어_스트림([
			'event:sources\ndata:[]\n\n',
			'event:text\ndata:부분 답변\n\n',
			'event:error\ndata:게이트웨이 다운\n\n',
		]);

		const parts = 파트들(await 읽는다(translate(코어)));

		assert.deepEqual(
			parts.map((p) => p.type),
			['start', 'data-sources', 'text-start', 'text-delta', 'text-end', 'error'],
		);
		assert.equal(parts.at(-1)?.errorText, '게이트웨이 다운');
	});

	it('코어가 done 없이 끊기면 error로 닫는다. 무한 대기가 없다 (REL-1)', async () => {
		const 코어 = 코어_스트림(['event:text\ndata:끊긴 답변\n\n']);

		const parts = 파트들(await 읽는다(translate(코어)));

		assert.equal(parts.at(-1)?.type, 'error');
	});
});
