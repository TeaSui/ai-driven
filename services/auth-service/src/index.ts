import express from 'express';
import cors from 'cors';
import { createLogger } from '@ai-driven/common';
import { authRouter } from './routes/auth.routes';

const logger = createLogger('auth-service');
const app = express();
const PORT = process.env.PORT || 3001;

app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'auth-service', timestamp: new Date().toISOString() });
});

// Routes
app.use('/auth', authRouter);

app.listen(PORT, () => {
  logger.info(`Auth Service running on port ${PORT}`);
});

export default app;
