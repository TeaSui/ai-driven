FROM node:18-alpine AS builder

WORKDIR /app

# Copy shared dependencies first
COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

# Copy service
COPY services/api-gateway/package.json services/api-gateway/
COPY services/api-gateway/tsconfig.json services/api-gateway/
COPY services/api-gateway/src services/api-gateway/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/api-gateway && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/api-gateway/dist ./dist
COPY --from=builder /app/services/api-gateway/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3000

CMD ["node", "dist/index.js"]
