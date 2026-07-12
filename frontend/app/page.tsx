import { auth, signIn, signOut } from '@/auth';
import { Chat } from '@/components/Chat';
import { Sessions } from '@/components/Sessions';
import { ThemeToggle } from '@/components/ThemeToggle';

export default async function Home() {
	const session = await auth();

	// 세션 쿠키는 30일, Keycloak refresh token은 30분이다. 갱신에 실패하면 세션 객체는
	// 남아 있지만 그 안의 베어러는 죽어 있다. 그대로 두면 채팅 화면이 뜨고 모든 호출이
	// 401이 된다. 다시 로그인시킨다 (S25).
	if (!session || session.error) {
		return (
			<div className="gate">
				<div className="gate__brand">
					<span className="mark" aria-hidden="true" />
					llmhub
				</div>
				<p className="gate__tagline">사내 문서에서 근거를 찾아 답합니다</p>
				{session?.error && (
					<p className="gate__msg" role="alert">
						로그인이 만료되었습니다. 다시 로그인해 주세요.
					</p>
				)}
				<form
					action={async () => {
						'use server';
						await signIn('keycloak');
					}}
				>
					<button type="submit" className="gate__btn">
						Keycloak으로 로그인
					</button>
				</form>
			</div>
		);
	}

	const who = session.user?.name || session.user?.email || '사용자';
	const role = session.user?.role;

	return (
		<div className="app">
			<aside className="sidebar">
				<div className="sidebar__brand">
					<span className="mark" aria-hidden="true" />
					llmhub
				</div>

				<Sessions />

				<div className="sidebar__foot">
					<div className="sidebar__id">
						<span className="sidebar__user" title={who}>
							{who}
						</span>
						{role && (
							<span className="rolechip" title="이 역할의 접근 권한으로 문서를 검색합니다">
								{role}
							</span>
						)}
					</div>
					<div className="sidebar__actions">
						<ThemeToggle />
						<form
							action={async () => {
								'use server';
								await signOut();
							}}
						>
							<button type="submit" className="signout">
								로그아웃
							</button>
						</form>
					</div>
				</div>
			</aside>

			<main className="main">
				<Chat />
			</main>
		</div>
	);
}
