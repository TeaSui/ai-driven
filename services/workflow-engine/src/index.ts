import express from 'express';
import cors from 'cors';
import { createLogger } from '@ai-driven/common';
import { workflowRouter } from './routes/workflow.routes';

const logger = createLogger('workflow-engine');
const app = express();
const PORT = process.env.PORT || 3002;

app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'workflow-engine', timestamp: new Date().toISOString() });
});

// Routes
app.use('/workflows', workflowRouter);

app.listen(PORT, () => {
  logger.info(`Workflow Engine running on port ${PORT}`);
});

export default app;
