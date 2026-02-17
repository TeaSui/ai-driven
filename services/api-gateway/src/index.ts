import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { createLogger } from '@ai-driven/common';
import { setupRoutes } from './routes';
import { errorHandler } from './middleware/error-handler';
import { rateLimiter } from './middleware/rate-limiter';
import { requestLogger } from './middleware/request-logger';

const logger = createLogger('api-gateway');
const app = express();
const PORT = process.env.PORT || 3000;

// Global middleware
app.use(helmet());
app.use(cors());
app.use(express.json());
app.use(rateLimiter);
app.use(requestLogger);

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'api-gateway', timestamp: new Date().toISOString() });
});

// Setup service routes
setupRoutes(app);

// Error handling
app.use(errorHandler);

app.listen(PORT, () => {
  logger.info(`API Gateway running on port ${PORT}`);
});

export default app;
