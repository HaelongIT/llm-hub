import type { ReactNode } from 'react';
import { IBM_Plex_Mono, IBM_Plex_Sans_KR } from 'next/font/google';
import { SessionProvider } from 'next-auth/react';

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
			<body>
				{/*
				 * 활성 클라이언트 세션에서 토큰을 갱신하는 유일한 경로다 (리뷰 F3). BFF 데이터 경로는 getToken
				 * (복호화만)이라 갱신이 없고, jwt 콜백 갱신은 서버 렌더·/api/auth/* 에서만 돈다. SessionProvider가
				 * refetchInterval마다 /api/auth/session을 폴링해 jwt 콜백(→ 만료 임박 시 갱신)을 트리거하고 쿠키를
				 * 갱신한다. 세션에는 토큰이 없으므로(R-8) 이 폴링이 토큰을 브라우저에 노출하지 않는다.
				 */}
				<SessionProvider refetchInterval={60}>{children}</SessionProvider>
			</body>
		</html>
	);
}
