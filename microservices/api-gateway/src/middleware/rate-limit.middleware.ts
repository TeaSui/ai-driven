import rateLimit from 'express-rate-limit';
import { Logger } from '../utils/logger';

const logger = new Logger('api-gateway:rate-limit');

export const rateLimiter = rateLimit({
  windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS || '60000', 10),
  max: parseInt(process.env.RATE_LIMIT_MAX || '100', 10),
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => {
    // Rate limit per tenant + IP
    const tenantId = req.headers['x-tenant-id'] as string;
    const ip = req.ip || req.socket.remoteAddress || 'unknown';
    return tenantId ? `${tenantId}:${ip}` : ip;
  },
  handler: (req, res) => {
    logger.warn('Rate limit exceeded', {
      ip: req.ip,
      path: req.path,
      tenantId: req.headers['x-tenant-id'],
    });
    res.status(429).json({
      success: false,
      error: {
        code: 'TOO_MANY_REQUESTS',
        message: 'Too many requests, please try again later',
        timestamp: new Date().toISOString(),
      },
    });
  },
});
