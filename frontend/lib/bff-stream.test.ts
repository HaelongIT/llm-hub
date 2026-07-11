import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { DONE_LINE, translateCoreStream } from './ui-message-stream.ts';

/**
 * BFF 번역을 실제 스트림 위에서 확인한다.
 *
 * 코어가 보내는 바이트를 그대로 흘려 넣고, useChat이 읽을 줄들이 나오는지 본다. 청크 경계가 SSE 프레임
 * 중간을 자르는 경우가 실제로 일어나므로 그것도 재현한다.
 *
 * <b>라우트의 실제 함수를 부른다 (R-14).</b> 예전엔 여기 복제본을 두었는데, 그러면 라우트의 끊김 처리
 * (try/catch, SEC-3)를 지워도 이 테스트가 통과했다. 이제 라우트와 같은 함수를 검증한다.
 */
function translate(upstream: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
	return translateCoreStream(upstream, 'msg-1');
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

	it('클라이언트가 스트림을 취소하면 코어 업스트림도 취소된다 (R-17)', async () => {
		// 클라이언트가 끊었는데 BFF가 코어를 끝까지 읽으면 코어는 LLM 토큰을 계속 만든다. 취소를 전파한다.
		let 업스트림_취소됨 = false;
		const 코어 = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(new TextEncoder().encode('event:text\ndata:부분\n\n'));
			},
			cancel() {
				업스트림_취소됨 = true;
			},
		});

		const out = translate(코어);
		const reader = out.getReader();
		await reader.read(); // 한 청크 받고
		await reader.cancel(); // 탭 닫기

		assert.ok(업스트림_취소됨, '반환 스트림 취소가 코어 업스트림 취소로 전파되어야 한다 (R-17)');
	});

	it('마지막 프레임이 \\n\\n 없이 끝나도 잃지 않는다 (L-7)', async () => {
		// 코어가 done 프레임을 종결 빈 줄 없이 보내면 buffer에 남아 사라진다 → finish()가 오류로 닫는다.
		const 코어 = 코어_스트림(['event:text\ndata:답\n\n', 'event:done\ndata:trace-1']); // 끝에 \n\n 없음

		const parts = 파트들(await 읽는다(translate(코어)));

		assert.equal(parts.at(-1)?.type, 'finish', '마지막 프레임을 파싱해 정상 종료(finish)로 닫는다');
		assert.ok(!parts.some((p) => p.type === 'error'), '오류로 닫지 않는다');
	});

	it('업스트림 읽기가 예외로 터지면 고정 문구로 닫고 내부 예외를 흘리지 않는다 (SEC-3)', async () => {
		// 이 분기가 R-14의 핵심이다. 복제본에는 try/catch가 없어, 라우트의 이 처리를 지워도 무검출이었다.
		const 폭발하는_코어 = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.error(new Error('ES 10.0.0.5:9200 연결 실패 — 이 문구가 새면 안 된다'));
			},
		});

		const wire = await 읽는다(translate(폭발하는_코어));
		const parts = 파트들(wire);

		assert.equal(parts.at(-1)?.type, 'error');
		assert.equal(parts.at(-1)?.errorText, '요청을 처리하지 못했습니다.');
		assert.ok(!wire.includes('10.0.0.5'), '내부 예외 문구가 브라우저로 새면 안 된다 (SEC-3)');
		assert.ok(wire.endsWith(DONE_LINE), '[DONE]으로 끝난다');
	});
});
