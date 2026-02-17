FROM node:18-alpine AS builder

WORKDIR /app

COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

COPY services/auth-service/package.json services/auth-service/
COPY services/auth-service/tsconfig.json services/auth-service/
COPY services/auth-service/src services/auth-service/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/auth-service && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/auth-service/dist ./dist
COPY --from=builder /app/services/auth-service/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3001

CMD ["node", "dist/index.js"]
