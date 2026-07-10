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
	const { messages, setMessages, sendMessage, status, error } = useChat({ transport });

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

	if (!sessionId) {
		return <section aria-live="polite">왼쪽에서 대화를 선택하거나 새로 시작하세요.</section>;
	}

	return (
		<section>
			{messages.map((message) => (
				<article key={message.id}>
					<strong>{message.role === 'user' ? '나' : '도우미'}</strong>
					{message.parts.map((part, i) => {
						if (part.type === 'text') {
							return <p key={i}>{part.text}</p>;
						}
						if (part.type === 'data-sources') {
							const sources = part.data as Source[];
							return (
								<ul key={i}>
									{sources.map((source) => (
										<li key={`${source.documentId}:${source.location}`}>
											<cite>
												{source.documentName} ({source.location})
											</cite>
											<blockquote>{source.text}</blockquote>
										</li>
									))}
								</ul>
							);
						}
						return null;
					})}
				</article>
			))}

			{error && <p role="alert">응답이 중단되었습니다: {error.message}</p>}

			<form
				onSubmit={(event) => {
					event.preventDefault();
					if (!input.trim()) return;
					sendMessage({ text: input });
					setInput('');
				}}
			>
				<input
					value={input}
					onChange={(event) => setInput(event.target.value)}
					placeholder="문서에 대해 물어보세요"
					disabled={status === 'streaming'}
				/>
				<button type="submit" disabled={status === 'streaming'}>
					보내기
				</button>
			</form>
		</section>
	);
}
