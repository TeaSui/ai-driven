import { Application } from 'express';
import { createProxyMiddleware } from 'http-proxy-middleware';
import { authMiddleware } from '../middleware/auth.middleware';
import { Logger } from '../utils/logger';

const logger = new Logger('api-gateway:proxy');

const AUTH_SERVICE_URL = process.env.AUTH_SERVICE_URL || 'http://auth-service:8081';
const WORKFLOW_SERVICE_URL = process.env.WORKFLOW_SERVICE_URL || 'http://workflow-service:8082';
const INTEGRATION_SERVICE_URL = process.env.INTEGRATION_SERVICE_URL || 'http://integration-service:8083';
const NOTIFICATION_SERVICE_URL = process.env.NOTIFICATION_SERVICE_URL || 'http://notification-service:8084';

function makeProxy(target: string, pathRewrite?: Record<string, string>) {
  return createProxyMiddleware({
    target,
    changeOrigin: true,
    pathRewrite,
    on: {
      error: (err, _req, res) => {
        logger.error(`Proxy error to ${target}`, err as Error);
        if (!res.headersSent) {
          (res as import('express').Response).status(502).json({
            success: false,
            error: {
              code: 'BAD_GATEWAY',
              message: 'Upstream service unavailable',
              timestamp: new Date().toISOString(),
            },
          });
        }
      },
    },
  });
}

export function createProxyRoutes(app: Application): void {
  // ─── Auth Service (public routes) ─────────────────────────────
  app.use(
    '/api/v1/auth',
    makeProxy(AUTH_SERVICE_URL, { '^/api/v1/auth': '/api/v1/auth' })
  );

  // ─── Auth Service (protected routes) ──────────────────────────
  app.use(
    '/api/v1/users',
    authMiddleware,
    makeProxy(AUTH_SERVICE_URL, { '^/api/v1/users': '/api/v1/users' })
  );

  // ─── Workflow Service ──────────────────────────────────────────
  app.use(
    '/api/v1/workflows',
    authMiddleware,
    makeProxy(WORKFLOW_SERVICE_URL, { '^/api/v1/workflows': '/api/v1/workflows' })
  );

  app.use(
    '/api/v1/executions',
    authMiddleware,
    makeProxy(WORKFLOW_SERVICE_URL, { '^/api/v1/executions': '/api/v1/executions' })
  );

  // ─── Integration Service ───────────────────────────────────────
  app.use(
    '/api/v1/integrations',
    authMiddleware,
    makeProxy(INTEGRATION_SERVICE_URL, { '^/api/v1/integrations': '/api/v1/integrations' })
  );

  // ─── Notification Service ──────────────────────────────────────
  app.use(
    '/api/v1/notifications',
    authMiddleware,
    makeProxy(NOTIFICATION_SERVICE_URL, { '^/api/v1/notifications': '/api/v1/notifications' })
  );

  app.use(
    '/api/v1/templates',
    authMiddleware,
    makeProxy(NOTIFICATION_SERVICE_URL, { '^/api/v1/templates': '/api/v1/templates' })
  );

  logger.info('Proxy routes configured', {
    routes: [
      '/api/v1/auth -> auth-service',
      '/api/v1/users -> auth-service',
      '/api/v1/workflows -> workflow-service',
      '/api/v1/executions -> workflow-service',
      '/api/v1/integrations -> integration-service',
      '/api/v1/notifications -> notification-service',
      '/api/v1/templates -> notification-service',
    ],
  });
}
