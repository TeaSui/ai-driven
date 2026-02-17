import express from 'express';
import cors from 'cors';
import { createLogger } from '@ai-driven/common';
import { tenantRouter } from './routes/tenant.routes';

const logger = createLogger('tenant-service');
const app = express();
const PORT = process.env.PORT || 3005;

app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'tenant-service', timestamp: new Date().toISOString() });
});

// Routes
app.use('/tenants', tenantRouter);

app.listen(PORT, () => {
  logger.info(`Tenant Service running on port ${PORT}`);
});

export default app;
