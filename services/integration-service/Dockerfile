FROM node:18-alpine AS builder

WORKDIR /app

COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

COPY services/integration-service/package.json services/integration-service/
COPY services/integration-service/tsconfig.json services/integration-service/
COPY services/integration-service/src services/integration-service/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/integration-service && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/integration-service/dist ./dist
COPY --from=builder /app/services/integration-service/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3003

CMD ["node", "dist/index.js"]
