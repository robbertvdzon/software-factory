# syntax=docker/dockerfile:1.7

FROM ghcr.io/cirruslabs/flutter:3.35.0 AS build
WORKDIR /src
COPY pubspec.yaml pubspec.lock ./
RUN flutter pub get
COPY . .
ARG API_BASE_URL=
ARG BUILD_SHA=dev
ARG BUILD_TIMESTAMP=dev
RUN flutter build web --release \
      --dart-define=API_BASE_URL=${API_BASE_URL} \
      --dart-define=BUILD_SHA=${BUILD_SHA} \
      --dart-define=BUILD_TIMESTAMP=${BUILD_TIMESTAMP} \
 && perl -0pi -e 's/serviceWorkerSettings:\s*\{\s*serviceWorkerVersion:\s*"[^"]+"\s*\}/serviceWorkerSettings: null/s' build/web/flutter_bootstrap.js \
 && perl -0pi -e "s/\"mainJsPath\":\"main\\.dart\\.js\"/\"mainJsPath\":\"main.dart.js?v=${BUILD_SHA}\"/g" build/web/flutter_bootstrap.js \
 && rm -f build/web/flutter_service_worker.js

FROM nginxinc/nginx-unprivileged:1.27-alpine
USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g+rwX /etc/nginx /usr/share/nginx/html /var/cache/nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html
USER 101
EXPOSE 8080
