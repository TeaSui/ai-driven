import { Express, Request, Response } from 'express';
import { createProxyMiddleware } from 'http-proxy-middleware';
import { createLogger } from '@ai-driven/common';

const logger = createLogger('api-gateway:routes');

const SERVICE_URLS = {
  auth: process.env.AUTH_SERVICE_URL || 'http://localhost:3001',
  workflow: process.env.WORKFLOW_SERVICE_URL || 'http://localhost:3002',
  integration: process.env.INTEGRATION_SERVICE_URL || 'http://localhost:3003',
  notification: process.env.NOTIFICATION_SERVICE_URL || 'http://localhost:3004',
  tenant: process.env.TENANT_SERVICE_URL || 'http://localhost:3005',
};

export function setupRoutes(app: Express): void {
  // Auth service proxy
  app.use(
    '/api/auth',
    createProxyMiddleware({
      target: SERVICE_URLS.auth,
      changeOrigin: true,
      pathRewrite: { '^/api/auth': '/auth' },
      on: {
        error: (err) => logger.error('Auth proxy error', { error: err.message }),
      },
    }),
  );

  // Workflow service proxy
  app.use(
    '/api/workflows',
    createProxyMiddleware({
      target: SERVICE_URLS.workflow,
      changeOrigin: true,
      pathRewrite: { '^/api/workflows': '/workflows' },
      on: {
        error: (err) => logger.error('Workflow proxy error', { error: err.message }),
      },
    }),
  );

  // Integration service proxy
  app.use(
    '/api/integrations',
    createProxyMiddleware({
      target: SERVICE_URLS.integration,
      changeOrigin: true,
      pathRewrite: { '^/api/integrations': '/integrations' },
      on: {
        error: (err) => logger.error('Integration proxy error', { error: err.message }),
      },
    }),
  );

  // Notification service proxy
  app.use(
    '/api/notifications',
    createProxyMiddleware({
      target: SERVICE_URLS.notification,
      changeOrigin: true,
      pathRewrite: { '^/api/notifications': '/notifications' },
      on: {
        error: (err) => logger.error('Notification proxy error', { error: err.message }),
      },
    }),
  );

  // Tenant service proxy
  app.use(
    '/api/tenants',
    createProxyMiddleware({
      target: SERVICE_URLS.tenant,
      changeOrigin: true,
      pathRewrite: { '^/api/tenants': '/tenants' },
      on: {
        error: (err) => logger.error('Tenant proxy error', { error: err.message }),
      },
    }),
  );

  // Fallback
  app.use('/api/*', (_req: Request, res: Response) => {
    res.status(404).json({ success: false, error: { code: 'NOT_FOUND', message: 'Route not found' } });
  });
}
