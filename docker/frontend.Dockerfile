# syntax=docker/dockerfile:1.7
# 프론트 이미지. 빌드 컨텍스트는 pAIr-front/frontend (docker-compose.yml 의 frontend 서비스)
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# VITE_ 값(토스 클라이언트 키 등)은 pAIr-front/frontend 의 .env, .env.local 에서 읽혀 번들에 박힌다. 바꾸려면 그 파일을 고치고 다시 빌드한다.
# 여기서 ARG 로 빈 값을 넘기면 .env 의 값을 덮어쓰므로 ARG 는 쓰지 않는다 (.dockerignore 에서도 .env 를 빼지 않는다)
RUN npm run build

FROM nginx:1.27-alpine
# 정적 파일을 서빙하고 /api, /ws 는 백엔드 컨테이너로 넘긴다 (개발 서버의 proxy 와 같은 역할)
COPY <<"EOF2" /etc/nginx/conf.d/default.conf
server {
    listen 80;
    root /usr/share/nginx/html;
    # 컨테이너 이름은 요청할 때 풀어서, 백엔드가 늦게 떠도 nginx 가 죽지 않게 한다
    resolver 127.0.0.11 valid=10s;
    set $backend http://backend:8180;
    client_max_body_size 5m;

    location /api/ {
        proxy_pass $backend;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # AI 답변은 글자가 오는 대로 흘려 보내므로 버퍼링을 끈다
        proxy_buffering off;
        proxy_read_timeout 300s;
    }

    location /ws/ {
        proxy_pass $backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    # 다시 빌드하면 assets/ 파일 이름이 바뀐다. index.html 을 브라우저가 캐시하면 옛 파일을 계속 불러오므로 항상 새로 받게 한다
    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    location / {
        try_files $uri /index.html;
    }
}
EOF2
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
