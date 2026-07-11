import type { ReactNode } from 'react';
import { IBM_Plex_Mono, IBM_Plex_Sans_KR } from 'next/font/google';

import './globals.css';

// 본문·한글: Plex Sans KR. 시스템 데이터(위치·score·시각·id): Plex Mono. 같은 슈퍼패밀리.
const sans = IBM_Plex_Sans_KR({
	subsets: ['latin'],
	weight: ['400', '500', '600'],
	variable: '--font-sans',
	display: 'swap',
});
const mono = IBM_Plex_Mono({
	subsets: ['latin'],
	weight: ['400', '500'],
	variable: '--font-mono',
	display: 'swap',
});

export const metadata = {
	title: 'llmhub',
	description: '사내 문서 기반 RAG 챗 — 근거를 대는 답변',
};

export default function RootLayout({ children }: { children: ReactNode }) {
	return (
		<html lang="ko" className={`${sans.variable} ${mono.variable}`}>
			<body>{children}</body>
		</html>
	);
}
