FROM node:22-slim AS build
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:$PATH"
WORKDIR /app
COPY . .
RUN npm ci && npm run build

FROM nginx:alpine
COPY --from=build /app/out/release-public /usr/share/nginx/html