import type { ReactNode } from 'react';

export const metadata = {
	title: 'llmhub',
	description: '사내 문서 기반 RAG 챗',
};

export default function RootLayout({ children }: { children: ReactNode }) {
	return (
		<html lang="ko">
			<body>{children}</body>
		</html>
	);
}
