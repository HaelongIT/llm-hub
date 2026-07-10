import type { NextConfig } from 'next';

const config: NextConfig = {
	// BFF만 외부에 노출된다. 코어·ES·PG·LiteLLM은 내부망 전용이다 (SEC-1).
	reactStrictMode: true,
};

export default config;
