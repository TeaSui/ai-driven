import express from 'express';
import cors from 'cors';
import { createLogger } from '@ai-driven/common';
import { notificationRouter } from './routes/notification.routes';

const logger = createLogger('notification-service');
const app = express();
const PORT = process.env.PORT || 3004;

app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'notification-service', timestamp: new Date().toISOString() });
});

// Routes
app.use('/notifications', notificationRouter);

app.listen(PORT, () => {
  logger.info(`Notification Service running on port ${PORT}`);
});

export default app;
