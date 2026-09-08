# --- 빌드 단계 ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .

# Next.js는 빌드 시점에 NEXT_PUBLIC_* 를 클라이언트 번들에 인라인하므로, 이 값이 반드시
# 여기 있어야 합니다 — compose에서 런타임에만 넣으면 폴백 값이 구워진 채로 남아서,
# .env에 무엇을 적든 브라우저는 localhost를 호출합니다.
ARG NEXT_PUBLIC_API_BASE=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE
RUN npm run build

# --- 실행 단계 ---
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app ./
EXPOSE 3000
CMD ["npm", "run", "start"]
