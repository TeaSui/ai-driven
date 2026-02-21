import 'express-async-errors';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import morgan from 'morgan';
import { v4 as uuidv4 } from 'uuid';

import { createProxyRoutes } from './routes/proxy.routes';
import { errorHandler } from './middleware/error.middleware';
import { rateLimiter } from './middleware/rate-limit.middleware';
import { Logger } from './utils/logger';

const logger = new Logger('api-gateway');
const app = express();
const PORT = parseInt(process.env.PORT || '8080', 10);

// ─── Security Middleware ───────────────────────────────────────
app.use(helmet());
app.use(
  cors({
    origin: process.env.ALLOWED_ORIGINS?.split(',') || '*',
    methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization', 'X-Request-ID', 'X-Tenant-ID'],
    credentials: true,
  })
);

// ─── Request ID ────────────────────────────────────────────────
app.use((req, _res, next) => {
  req.headers['x-request-id'] = req.headers['x-request-id'] || uuidv4();
  next();
});

// ─── Logging ──────────────────────────────────────────────────
app.use(
  morgan('combined', {
    stream: {
      write: (message: string) =>
        logger.info('HTTP', { message: message.trim() }),
    },
  })
);

// ─── Rate Limiting ────────────────────────────────────────────
app.use(rateLimiter);

// ─── Health Check ─────────────────────────────────────────────
app.get('/health', (_req, res) => {
  res.json({
    status: 'healthy',
    service: 'api-gateway',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  });
});

// ─── Proxy Routes ─────────────────────────────────────────────
createProxyRoutes(app);

// ─── Error Handler ────────────────────────────────────────────
app.use(errorHandler);

// ─── 404 Handler ──────────────────────────────────────────────
app.use((_req, res) => {
  res.status(404).json({
    success: false,
    error: {
      code: 'NOT_FOUND',
      message: 'Route not found',
      timestamp: new Date().toISOString(),
    },
  });
});

app.listen(PORT, () => {
  logger.info(`API Gateway running on port ${PORT}`);
});

export default app;
