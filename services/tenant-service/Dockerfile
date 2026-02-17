FROM node:18-alpine AS builder

WORKDIR /app

COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

COPY services/tenant-service/package.json services/tenant-service/
COPY services/tenant-service/tsconfig.json services/tenant-service/
COPY services/tenant-service/src services/tenant-service/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/tenant-service && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/tenant-service/dist ./dist
COPY --from=builder /app/services/tenant-service/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3005

CMD ["node", "dist/index.js"]
