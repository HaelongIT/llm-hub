'use client';

import { useCallback, useEffect, useState } from 'react';

import { sessionTitle, type SessionSummary } from '@/lib/history';
import { useSessionStore } from '@/lib/store';
import { DATE_GROUP_ORDER, dateGroup, relativeTime } from '@/lib/time';

/**
 * 사이드바. 세션은 사용자 소유이며 생성·조회·삭제만 한다 (S2).
 *
 * 세션을 **명시적으로 먼저 만든다.** 세션 없이 질문하면 코어가 만들어 주지만, `done` 이벤트는
 * `trace_id`만 싣기 때문에(S6) 클라이언트가 새 세션 ID를 알 수 없다.
 */
export function Sessions() {
	const { currentSessionId, select } = useSessionStore();
	const [sessions, setSessions] = useState<SessionSummary[]>([]);
	const [error, setError] = useState<string | null>(null);
	const [confirmingId, setConfirmingId] = useState<string | null>(null);

	const load = useCallback(async () => {
		const response = await fetch('/api/sessions');
		if (!response.ok) {
			setError('세션 목록을 불러오지 못했습니다.');
			return;
		}
		setError(null);
		setSessions((await response.json()) as SessionSummary[]);
	}, []);

	useEffect(() => {
		void load();
	}, [load]);

	const createSession = useCallback(async () => {
		const response = await fetch('/api/sessions', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ title: '새 대화' }),
		});
		if (!response.ok) {
			setError('세션을 만들지 못했습니다.');
			return;
		}
		const { id } = (await response.json()) as { id: string };
		await load();
		select(id, '새 대화');
	}, [load, select]);

	// Cmd/Ctrl+K 로 새 대화.
	useEffect(() => {
		const onKey = (event: KeyboardEvent) => {
			if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
				event.preventDefault();
				void createSession();
			}
		};
		window.addEventListener('keydown', onKey);
		return () => window.removeEventListener('keydown', onKey);
	}, [createSession]);

	async function deleteSession(id: string) {
		setConfirmingId(null);
		const response = await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
		// 남의 세션은 코어가 404로 답한다. 목록에 없는 것을 지우려 한 셈이다.
		if (!response.ok && response.status !== 404) {
			setError('세션을 지우지 못했습니다.');
			return;
		}
		if (currentSessionId === id) select(null);
		await load();
	}

	// 날짜 그룹으로 묶는다. 목록은 코어가 최근 갱신순으로 주므로 그룹 안 순서는 그대로다.
	const groups = DATE_GROUP_ORDER.map((name) => ({
		name,
		items: sessions.filter((s) => dateGroup(s.updatedAt) === name),
	})).filter((g) => g.items.length > 0);

	return (
		<>
			<button type="button" className="newchat" onClick={() => void createSession()}>
				<span>＋ 새 대화</span>
				<kbd className="newchat__kbd">⌘K</kbd>
			</button>

			{error && (
				<p className="alert" role="alert">
					{error}
				</p>
			)}

			<nav className="sessions" aria-label="대화 목록">
				{groups.map((group) => (
					<div className="sessions__group" key={group.name}>
						<div className="sessions__group-label">{group.name}</div>
						<ul>
							{group.items.map((session) => {
								const title = sessionTitle(session);
								const active = session.id === currentSessionId;
								const confirming = confirmingId === session.id;
								return (
									<li
										key={session.id}
										className={active ? 'session session--active' : 'session'}
										aria-current={active ? 'true' : undefined}
									>
										{confirming ? (
											<div className="session__confirm">
												<span>삭제할까요?</span>
												<button type="button" className="session__yes" onClick={() => void deleteSession(session.id)}>
													예
												</button>
												<button type="button" className="session__no" onClick={() => setConfirmingId(null)}>
													취소
												</button>
											</div>
										) : (
											<>
												<button
													type="button"
													className="session__title"
													onClick={() => select(session.id, title)}
												>
													{title}
												</button>
												<span className="session__time">{relativeTime(session.updatedAt)}</span>
												<button
													type="button"
													className="session__delete"
													aria-label={`${title} 삭제`}
													onClick={() => setConfirmingId(session.id)}
												>
													삭제
												</button>
											</>
										)}
									</li>
								);
							})}
						</ul>
					</div>
				))}

				{sessions.length === 0 && !error && (
					<p className="sidebar__empty">대화가 없습니다. 새 대화를 시작하세요.</p>
				)}
			</nav>
		</>
	);
}
