import 'express-async-errors';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import { v4 as uuidv4 } from 'uuid';

import { authRouter } from './routes/auth.routes';
import { userRouter } from './routes/user.routes';
import { errorHandler } from './middleware/error.middleware';
import { Logger } from './utils/logger';
import { prisma } from './database/prisma';
import { connectRabbitMQ } from './messaging/rabbitmq';

const logger = new Logger('auth-service');
const app = express();
const PORT = parseInt(process.env.PORT || '8081', 10);

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
      service: 'auth-service',
      timestamp: new Date().toISOString(),
      uptime: process.uptime(),
    });
  } catch {
    res.status(503).json({ status: 'unhealthy', service: 'auth-service' });
  }
});

app.use('/api/v1/auth', authRouter);
app.use('/api/v1/users', userRouter);

app.use(errorHandler);

async function bootstrap() {
  try {
    await prisma.$connect();
    logger.info('Database connected');

    await connectRabbitMQ();
    logger.info('RabbitMQ connected');

    app.listen(PORT, () => {
      logger.info(`Auth Service running on port ${PORT}`);
    });
  } catch (error) {
    logger.error('Failed to start auth service', error as Error);
    process.exit(1);
  }
}

bootstrap();

export default app;
