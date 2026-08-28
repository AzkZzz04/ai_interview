FROM node:22-alpine AS dependencies

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build

WORKDIR /app
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .

ARG NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
RUN npm run build

FROM node:22-alpine AS runtime

ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 10001 nextjs \
  && adduser --system --uid 10001 --ingroup nextjs nextjs

WORKDIR /app
COPY --from=build --chown=nextjs:nextjs /app/.next-build/standalone ./
COPY --from=build --chown=nextjs:nextjs /app/.next-build/static ./.next-build/static

USER nextjs

EXPOSE 3000

CMD ["node", "server.js"]
