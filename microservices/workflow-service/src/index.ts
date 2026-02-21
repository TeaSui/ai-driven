import 'express-async-errors';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import { v4 as uuidv4 } from 'uuid';

import { workflowRouter } from './routes/workflow.routes';
import { executionRouter } from './routes/execution.routes';
import { errorHandler } from './middleware/error.middleware';
import { Logger } from './utils/logger';
import { prisma } from './database/prisma';
import { connectRabbitMQ, startConsumers } from './messaging/rabbitmq';
import { WorkflowScheduler } from './scheduler/workflow.scheduler';

const logger = new Logger('workflow-service');
const app = express();
const PORT = parseInt(process.env.PORT || '8082', 10);

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
    res.json({
      status: 'healthy',
      service: 'workflow-service',
      timestamp: new Date().toISOString(),
      uptime: process.uptime(),
    });
  } catch {
    res.status(503).json({ status: 'unhealthy', service: 'workflow-service' });
  }
});

app.use('/api/v1/workflows', workflowRouter);
app.use('/api/v1/executions', executionRouter);

app.use(errorHandler);

async function bootstrap() {
  try {
    await prisma.$connect();
    logger.info('Database connected');

    await connectRabbitMQ();
    await startConsumers();
    logger.info('RabbitMQ connected and consumers started');

    const scheduler = new WorkflowScheduler();
    await scheduler.start();
    logger.info('Workflow scheduler started');

    app.listen(PORT, () => {
      logger.info(`Workflow Service running on port ${PORT}`);
    });
  } catch (error) {
    logger.error('Failed to start workflow service', error as Error);
    process.exit(1);
  }
}

bootstrap();

export default app;
