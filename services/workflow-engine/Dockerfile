FROM node:18-alpine AS builder

WORKDIR /app

COPY shared/common/package.json shared/common/
COPY shared/common/tsconfig.json shared/common/
COPY shared/common/src shared/common/src

COPY services/workflow-engine/package.json services/workflow-engine/
COPY services/workflow-engine/tsconfig.json services/workflow-engine/
COPY services/workflow-engine/src services/workflow-engine/src

COPY tsconfig.base.json .
COPY package.json .

RUN npm install
RUN cd shared/common && npm run build
RUN cd services/workflow-engine && npm run build

FROM node:18-alpine

WORKDIR /app

COPY --from=builder /app/services/workflow-engine/dist ./dist
COPY --from=builder /app/services/workflow-engine/package.json .
COPY --from=builder /app/node_modules ./node_modules

EXPOSE 3002

CMD ["node", "dist/index.js"]
