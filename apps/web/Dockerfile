FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG VITE_API_BASE_URL=http://127.0.0.1:8080
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
# "all" builds a demo image that runs on mock data; production keeps mocks off.
ARG VITE_API_MOCKS=off
ENV VITE_API_MOCKS=${VITE_API_MOCKS}
RUN npm run build

FROM nginxinc/nginx-unprivileged:alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 3000
