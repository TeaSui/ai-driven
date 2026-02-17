FROM node:18-alpine AS builder

WORKDIR /app

COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

COPY services/notification-service/package.json services/notification-service/
COPY services/notification-service/tsconfig.json services/notification-service/
COPY services/notification-service/src services/notification-service/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/notification-service && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/notification-service/dist ./dist
COPY --from=builder /app/services/notification-service/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3004

CMD ["node", "dist/index.js"]
