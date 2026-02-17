import express from 'express';
import cors from 'cors';
import { createLogger } from '@ai-driven/common';
import { integrationRouter } from './routes/integration.routes';
import { ConnectorRegistry } from './registry/connector.registry';
import { SlackConnector } from './connectors/slack.connector';
import { WebhookConnector } from './connectors/webhook.connector';
import { EmailConnector } from './connectors/email.connector';

const logger = createLogger('integration-service');
const app = express();
const PORT = process.env.PORT || 3003;

// Initialize connector registry with built-in connectors
const registry = ConnectorRegistry.getInstance();
registry.register(new SlackConnector());
registry.register(new WebhookConnector());
registry.register(new EmailConnector());

app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({
    status: 'healthy',
    service: 'integration-service',
    connectors: registry.getAll().map((c) => c.type),
    timestamp: new Date().toISOString(),
  });
});

// Routes
app.use('/integrations', integrationRouter);

app.listen(PORT, () => {
  logger.info(`Integration Service running on port ${PORT}`, {
    connectors: registry.getAll().map((c) => c.type),
  });
});

export default app;
