import { auth, signIn, signOut } from '@/auth';
import { Chat } from '@/components/Chat';
import { Sessions } from '@/components/Sessions';

export default async function Home() {
	const session = await auth();

	// 세션 쿠키는 30일, Keycloak refresh token은 30분이다. 갱신에 실패하면 세션 객체는
	// 남아 있지만 그 안의 베어러는 죽어 있다. 그대로 두면 채팅 화면이 뜨고 모든 호출이
	// 401이 된다. 다시 로그인시킨다 (S25).
	if (!session || session.error) {
		return (
			<main>
				<h1>llmhub</h1>
				{session?.error && <p role="alert">로그인이 만료되었습니다. 다시 로그인해 주세요.</p>}
				<form
					action={async () => {
						'use server';
						await signIn('keycloak');
					}}
				>
					<button type="submit">Keycloak으로 로그인</button>
				</form>
			</main>
		);
	}

	return (
		<main>
			<header>
				<h1>llmhub</h1>
				<form
					action={async () => {
						'use server';
						await signOut();
					}}
				>
					<button type="submit">로그아웃</button>
				</form>
			</header>

			<div>
				<Sessions />
				<Chat />
			</div>
		</main>
	);
}
