import 'express-async-errors';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import { v4 as uuidv4 } from 'uuid';

import { integrationRouter } from './routes/integration.routes';
import { errorHandler } from './middleware/error.middleware';
import { Logger } from './utils/logger';
import { prisma } from './database/prisma';
import { connectRabbitMQ } from './messaging/rabbitmq';

const logger = new Logger('integration-service');
const app = express();
const PORT = parseInt(process.env.PORT || '8083', 10);

app.use(helmet());
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

app.use((req, _res, next) => {
  req.headers['x-request-id'] = req.headers['x-request-id'] || uuidv4();
  next();
});

app.get('/health', async (_req, res) => {
  try {
    await prisma.$queryRaw`SELECT 1`;
    res.json({ status: 'healthy', service: 'integration-service', timestamp: new Date().toISOString() });
  } catch {
    res.status(503).json({ status: 'unhealthy', service: 'integration-service' });
  }
});

app.use('/api/v1/integrations', integrationRouter);

app.use(errorHandler);

async function bootstrap() {
  try {
    await prisma.$connect();
    logger.info('Database connected');
    await connectRabbitMQ();
    logger.info('RabbitMQ connected');
    app.listen(PORT, () => logger.info(`Integration Service running on port ${PORT}`));
  } catch (error) {
    logger.error('Failed to start integration service', error as Error);
    process.exit(1);
  }
}

bootstrap();
export default app;
