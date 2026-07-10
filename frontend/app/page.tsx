import { auth, signIn } from '@/auth';
import { Chat } from '@/components/Chat';

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
			<h1>llmhub</h1>
			<Chat />
		</main>
	);
}
