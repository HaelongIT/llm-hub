'use client';

import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport } from 'ai';
import { useEffect, useMemo, useRef, useState } from 'react';

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

const MAX_CHARS = 4000;
const COUNT_FROM = 3600;
const QUOTE_CLAMP = 180;
const EXAMPLES = ['연차휴가는 며칠인가요?', '출장 규정을 알려줘', '육아휴직 조건은?'];

function scorePct(score: number, max: number): number {
	return max > 0 ? Math.round((score / max) * 100) : 0;
}

/** 증거 슬립 하나. 인용이 길면 접고 "더 보기"로 편다. */
function Slip({ source, max }: { source: Source; max: number }) {
	const [open, setOpen] = useState(false);
	const long = source.text.length > QUOTE_CLAMP;
	const shown = long && !open ? `${source.text.slice(0, QUOTE_CLAMP)}…` : source.text;
	return (
		<div className="slip">
			<div className="slip__head">
				<span className="slip__doc" title={source.documentName}>
					{source.documentName}
				</span>
				<span className="slip__loc">· {source.location}</span>
				<span className="slip__score" title="하이브리드 검색 관련도">
					<span className="scorebar" aria-hidden="true">
						<span className="scorebar__fill" style={{ width: `${scorePct(source.score, max)}%` }} />
					</span>
					<span className="slip__num">{source.score.toFixed(2)}</span>
				</span>
			</div>
			<blockquote className="slip__quote">{shown}</blockquote>
			{long && (
				<button type="button" className="slip__more" onClick={() => setOpen((v) => !v)}>
					{open ? '접기' : '더 보기'}
				</button>
			)}
		</div>
	);
}

/** 답변보다 먼저 도착한 근거. 서버가 실제로 검색한 조각이다 (S6). */
function Evidence({ sources }: { sources: Source[] }) {
	if (sources.length === 0) {
		return (
			<p className="evidence--empty">관련 근거를 찾지 못했습니다. 다르게 물어보거나 접근 권한을 확인하세요.</p>
		);
	}
	const max = Math.max(...sources.map((s) => s.score));
	return (
		<div className="evidence">
			<span className="evidence__label">근거 {sources.length} · 관련도순</span>
			{sources.map((source) => (
				<Slip key={`${source.documentId}:${source.location}`} source={source} max={max} />
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

export function Chat() {
	const sessionId = useSessionStore((s) => s.currentSessionId);
	const currentTitle = useSessionStore((s) => s.currentTitle);
	const [input, setInput] = useState('');
	const [copiedId, setCopiedId] = useState<string | null>(null);

	const threadRef = useRef<HTMLDivElement>(null);
	const taRef = useRef<HTMLTextAreaElement>(null);
	const stick = useRef(true);
	const composing = useRef(false);

	const transport = useMemo(
		() => new DefaultChatTransport({ api: '/api/chat/stream', body: { sessionId } }),
		[sessionId],
	);
	// id에 sessionId를 준다. 없으면 세션을 바꿔도 인스턴스가 재사용돼 이전 세션 error/메시지가 섞인다 (L-8).
	const { messages, setMessages, sendMessage, status, error, stop, regenerate } = useChat({
		id: sessionId ?? undefined,
		transport,
	});

	// 세션을 바꾸면 그 세션의 이력을 불러온다. 세션이 없으면 화면을 비운다.
	useEffect(() => {
		let cancelled = false;
		if (!sessionId) {
			setMessages([]);
			return;
		}
		void (async () => {
			const response = await fetch(`/api/sessions/${sessionId}/messages`);
			if (!response.ok || cancelled) return;
			const history = (await response.json()) as CoreMessage[];
			if (!cancelled) setMessages(toUiMessages(history));
		})();
		return () => {
			cancelled = true;
		};
	}, [sessionId, setMessages]);

	// 스트리밍 중 하단 고정. 사용자가 위로 올리면 해제한다.
	useEffect(() => {
		const el = threadRef.current;
		if (el && stick.current) el.scrollTop = el.scrollHeight;
	}, [messages, status]);

	const busy = status === 'streaming' || status === 'submitted';
	const tooLong = input.length > MAX_CHARS;
	const awaitingAnswer = status === 'submitted' && messages.at(-1)?.role === 'user';

	function onScroll() {
		const el = threadRef.current;
		if (!el) return;
		stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
	}

	function grow() {
		const el = taRef.current;
		if (!el) return;
		el.style.height = 'auto';
		el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
	}

	function submit() {
		const text = input.trim();
		if (!text || busy || tooLong) return;
		stick.current = true;
		sendMessage({ text });
		setInput('');
		requestAnimationFrame(() => {
			if (taRef.current) taRef.current.style.height = 'auto';
		});
	}

	function onKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
		// 한글 조합 중 Enter는 확정이지 전송이 아니다.
		if (event.key === 'Enter' && !event.shiftKey && !composing.current) {
			event.preventDefault();
			submit();
		}
	}

	async function copy(id: string, text: string) {
		try {
			await navigator.clipboard.writeText(text);
			setCopiedId(id);
			setTimeout(() => setCopiedId((c) => (c === id ? null : c)), 1500);
		} catch {
			// 클립보드 접근이 막히면 조용히 지나간다.
		}
	}

	if (!sessionId) {
		return (
			<div className="empty">
				<p className="empty__lead">사내 문서에서 근거를 찾아 답합니다.</p>
				<p className="empty__sub">왼쪽에서 새 대화를 시작하세요.</p>
			</div>
		);
	}

	return (
		<>
			<header className="main__header">{currentTitle || '새 대화'}</header>

			<div className="thread" ref={threadRef} onScroll={onScroll}>
				{messages.length === 0 && !busy ? (
					<div className="welcome">
						<p className="welcome__lead">무엇이 궁금하세요?</p>
						<p className="welcome__sub">사내 문서에서 근거를 찾아 답합니다. 예를 들어—</p>
						<div className="welcome__chips">
							{EXAMPLES.map((q) => (
								<button
									key={q}
									type="button"
									className="chip"
									onClick={() => {
										stick.current = true;
										sendMessage({ text: q });
									}}
								>
									{q}
								</button>
							))}
						</div>
					</div>
				) : (
					messages.map((message, index) => {
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

						const grounded = sources && sources.length > 0 && !!text && !streamingThis;
						return (
							<article className="msg msg--assistant" key={message.id}>
								{sources && <Evidence sources={sources} />}
								<div className="answer__head">
									<span className="msg__label">답변</span>
									{text && !streamingThis && (
										<button
											type="button"
											className="answer__copy"
											onClick={() => void copy(message.id, text)}
										>
											{copiedId === message.id ? '복사됨' : '복사'}
										</button>
									)}
								</div>
								{text ? (
									<div className="answer">{text}</div>
								) : streamingThis ? (
									<Thinking label="근거를 종합하는 중" />
								) : null}
								{grounded && <div className="answer__grounded">근거 {sources.length}개로 답함</div>}
							</article>
						);
					})
				)}

				{awaitingAnswer && (
					<article className="msg msg--assistant">
						<div className="msg__label">답변</div>
						<Thinking label="근거를 검색하는 중" />
					</article>
				)}

				{error && (
					<div className="banner" role="alert">
						<span>응답이 중단되었습니다.</span>
						<button type="button" className="banner__action" onClick={() => void regenerate()}>
							다시 시도
						</button>
					</div>
				)}
			</div>

			<form
				className="composer"
				onSubmit={(event) => {
					event.preventDefault();
					submit();
				}}
			>
				<div className="composer__row">
					<textarea
						ref={taRef}
						className="composer__input"
						rows={1}
						value={input}
						onChange={(event) => {
							setInput(event.target.value);
							grow();
						}}
						onKeyDown={onKeyDown}
						onCompositionStart={() => {
							composing.current = true;
						}}
						onCompositionEnd={() => {
							composing.current = false;
						}}
						placeholder="문서에 대해 물어보세요"
						aria-label="질문"
					/>
					{busy ? (
						<button type="button" className="composer__stop" onClick={() => stop()}>
							중지
						</button>
					) : (
						<button type="submit" className="composer__send" disabled={!input.trim() || tooLong}>
							보내기
						</button>
					)}
				</div>
				{input.length > COUNT_FROM && (
					<div className={tooLong ? 'composer__count composer__count--over' : 'composer__count'}>
						{input.length.toLocaleString()} / {MAX_CHARS.toLocaleString()}
						{tooLong && ' — 질문이 너무 깁니다'}
					</div>
				)}
			</form>
		</>
	);
}
