import { auth, signIn, signOut } from '@/auth';
import { Chat } from '@/components/Chat';
import { Sessions } from '@/components/Sessions';

export default async function Home() {
	const session = await auth();

	if (!session) {
		return (
			<main>
				<h1>llmhub</h1>
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
