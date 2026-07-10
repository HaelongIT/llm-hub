# BFF + UI 이미지.
#
# 이것만 외부에 노출된다. 코어·Elasticsearch·PostgreSQL·LiteLLM은 내부망 전용이다 (SEC-1).

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# next build는 빌드 시점에 환경변수를 요구하지 않는다. 인증·코어 주소는 런타임 값이다.
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 루트로 돌리지 않는다.
RUN addgroup --system --gid 1001 llmhub && adduser --system --uid 1001 --ingroup llmhub llmhub

# standalone 출력은 실행에 필요한 node_modules만 담는다.
COPY --from=build --chown=llmhub:llmhub /app/.next/standalone ./
COPY --from=build --chown=llmhub:llmhub /app/.next/static ./.next/static

USER llmhub
EXPOSE 3000

CMD ["node", "server.js"]
