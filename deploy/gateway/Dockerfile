# syntax=docker/dockerfile:1.7
# API gateway with the web UI: builds frontend/ and serves it next to the API routes (one origin, no CORS).
#   docker build -f deploy/gateway/Dockerfile .
FROM node:24-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build && rm -f dist/config.json

FROM nginx:1.27-alpine
COPY deploy/gateway/nginx.conf /etc/nginx/nginx.conf
COPY --from=ui /ui/dist /usr/share/nginx/html
