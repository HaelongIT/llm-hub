import type { NextConfig } from 'next';

const config: NextConfig = {
	// BFF만 외부에 노출된다. 코어·ES·PG·LiteLLM은 내부망 전용이다 (SEC-1).
	reactStrictMode: true,
	// 설치형 배포용 이미지를 위해 실행에 필요한 것만 모은 출력을 만든다 (S26/S27).
	output: 'standalone',
};

export default config;
