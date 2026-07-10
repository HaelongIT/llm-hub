'use client';

import { useCallback, useEffect, useState } from 'react';

import { sessionTitle, type SessionSummary } from '@/lib/history';
import { useSessionStore } from '@/lib/store';

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

	async function createSession() {
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
		select(id);
	}

	async function deleteSession(id: string) {
		const response = await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
		// 남의 세션은 코어가 404로 답한다. 목록에 없는 것을 지우려 한 셈이다.
		if (!response.ok && response.status !== 404) {
			setError('세션을 지우지 못했습니다.');
			return;
		}
		if (currentSessionId === id) {
			select(null);
		}
		await load();
	}

	return (
		<nav aria-label="대화 목록">
			<button type="button" onClick={() => void createSession()}>
				새 대화
			</button>

			{error && <p role="alert">{error}</p>}

			<ul>
				{sessions.map((session) => (
					<li key={session.id} aria-current={session.id === currentSessionId ? 'true' : undefined}>
						<button type="button" onClick={() => select(session.id)}>
							{sessionTitle(session)}
						</button>
						<button type="button" aria-label={`${sessionTitle(session)} 삭제`} onClick={() => void deleteSession(session.id)}>
							삭제
						</button>
					</li>
				))}
			</ul>

			{sessions.length === 0 && !error && <p>대화가 없습니다. 새 대화를 시작하세요.</p>}
		</nav>
	);
}
