'use client';

import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport } from 'ai';
import { useEffect, useMemo, useState } from 'react';

import { toUiMessages, type CoreMessage } from '@/lib/history';
import { useSessionStore } from '@/lib/store';

/** 근거 한 건. 코어의 Source와 같은 모양이다. */
type Source = {
	documentId: string;
	documentName: string;
	location: string;
	text: string;
	score: number;
};

function scorePct(score: number, max: number): number {
	return max > 0 ? Math.round((score / max) * 100) : 0;
}

/** 답변보다 먼저 도착한 근거를 "증거 슬립"으로. 서버가 실제로 검색한 조각이다 (S6). */
function Evidence({ sources }: { sources: Source[] }) {
	if (sources.length === 0) {
		return <p className="evidence--empty">관련 근거를 찾지 못했습니다.</p>;
	}
	const max = Math.max(...sources.map((s) => s.score));
	return (
		<div className="evidence">
			<span className="evidence__label">근거 {sources.length}</span>
			{sources.map((source) => (
				<div className="slip" key={`${source.documentId}:${source.location}`}>
					<div className="slip__head">
						<span className="slip__doc" title={source.documentName}>
							{source.documentName}
						</span>
						<span className="slip__loc">· {source.location}</span>
						<span className="slip__score">
							<span className="scorebar" aria-hidden="true">
								<span className="scorebar__fill" style={{ width: `${scorePct(source.score, max)}%` }} />
							</span>
							<span className="slip__num">{source.score.toFixed(2)}</span>
						</span>
					</div>
					<blockquote className="slip__quote">{source.text}</blockquote>
				</div>
			))}
		</div>
	);
}

function Thinking({ label }: { label: string }) {
	return (
		<div className="streaming" aria-live="polite">
			{label}
			<span className="dots" aria-hidden="true">
				<span />
				<span />
				<span />
			</span>
		</div>
	);
}

/**
 * 근거는 커스텀 `data-sources` 파트로 온다. 내장 source-document 파트는 위치·점수를 싣지 못한다.
 *
 * 이 근거는 서버가 실제로 검색한 조각이다. LLM이 지어낸 것이 아니다 (S6).
 */
export function Chat() {
	const sessionId = useSessionStore((s) => s.currentSessionId);
	const [input, setInput] = useState('');

	// v5부터 useChat은 body를 직접 받지 않는다. transport가 요청을 만든다.
	const transport = useMemo(
		() => new DefaultChatTransport({ api: '/api/chat/stream', body: { sessionId } }),
		[sessionId],
	);
	// id에 sessionId를 준다. 없으면 useChat이 세션을 바꿔도 같은 인스턴스를 재사용해, 이전 세션의 error
	// 배너가 남고 스트리밍 중 전환하면 메시지가 섞인다 (L-8). 세션마다 별개 대화로 다룬다.
	const { messages, setMessages, sendMessage, status, error } = useChat({ id: sessionId ?? undefined, transport });

	// 세션을 바꾸면 그 세션의 이력을 불러온다. 세션이 없으면 화면을 비운다.
	useEffect(() => {
		let cancelled = false;

		if (!sessionId) {
			setMessages([]);
			return;
		}

		void (async () => {
			const response = await fetch(`/api/sessions/${sessionId}/messages`);
			if (!response.ok || cancelled) {
				return;
			}
			const history = (await response.json()) as CoreMessage[];
			if (!cancelled) {
				setMessages(toUiMessages(history));
			}
		})();

		return () => {
			cancelled = true;
		};
	}, [sessionId, setMessages]);

	const busy = status === 'streaming' || status === 'submitted';
	// 답변 청크가 아직 없을 때(검색 중)를 위한 대기 인디케이터.
	const awaitingAnswer = status === 'submitted' && messages.at(-1)?.role === 'user';

	if (!sessionId) {
		return <div className="empty">질문을 시작하려면 왼쪽에서 새 대화를 여세요.</div>;
	}

	return (
		<>
			<div className="thread">
				{messages.map((message, index) => {
					const text = message.parts
						.filter((part) => part.type === 'text')
						.map((part) => (part as { text: string }).text)
						.join('');
					const sourcesPart = message.parts.find((part) => part.type === 'data-sources');
					const sources = sourcesPart ? ((sourcesPart as { data: Source[] }).data ?? []) : null;
					const streamingThis = status === 'streaming' && index === messages.length - 1;

					if (message.role === 'user') {
						return (
							<article className="msg msg--user" key={message.id}>
								<div className="msg__label">질문</div>
								<div className="question">{text}</div>
							</article>
						);
					}

					return (
						<article className="msg msg--assistant" key={message.id}>
							{sources && <Evidence sources={sources} />}
							<div className="msg__label">답변</div>
							{text ? (
								<div className="answer">{text}</div>
							) : streamingThis ? (
								<Thinking label="근거를 종합하는 중" />
							) : null}
						</article>
					);
				})}

				{awaitingAnswer && (
					<article className="msg msg--assistant">
						<div className="msg__label">답변</div>
						<Thinking label="근거를 검색하는 중" />
					</article>
				)}

				{error && (
					<p className="alert" role="alert">
						{error.message}
					</p>
				)}
			</div>

			<form
				className="composer"
				onSubmit={(event) => {
					event.preventDefault();
					if (!input.trim()) return;
					sendMessage({ text: input });
					setInput('');
				}}
			>
				<div className="composer__row">
					<input
						className="composer__input"
						value={input}
						onChange={(event) => setInput(event.target.value)}
						placeholder="문서에 대해 물어보세요"
						aria-label="질문"
						disabled={busy}
					/>
					<button type="submit" className="composer__send" disabled={busy || !input.trim()}>
						보내기
					</button>
				</div>
			</form>
		</>
	);
}
