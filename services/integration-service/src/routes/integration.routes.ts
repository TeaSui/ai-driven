import { Router, Request, Response } from 'express';
import { ConnectorRegistry } from '../registry/connector.registry';
import { createSuccessResponse, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('integration-service:routes');
const registry = ConnectorRegistry.getInstance();
export const integrationRouter = Router();

// List available connectors
integrationRouter.get('/connectors', (_req: Request, res: Response) => {
  const connectors = registry.getAll().map((c) => ({
    type: c.type,
    name: c.name,
    actions: c.getAvailableActions(),
  }));
  res.json(createSuccessResponse(connectors));
});

// Get connector details
integrationRouter.get('/connectors/:type', (req: Request, res: Response) => {
  const connector = registry.get(req.params.type);
  if (!connector) {
    res.status(404).json(createErrorResponse('NOT_FOUND', `Connector '${req.params.type}' not found`));
    return;
  }

  res.json(
    createSuccessResponse({
      type: connector.type,
      name: connector.name,
      actions: connector.getAvailableActions(),
    }),
  );
});

// Execute connector action
integrationRouter.post('/connectors/:type/execute', async (req: Request, res: Response) => {
  try {
    const connector = registry.get(req.params.type);
    if (!connector) {
      res.status(404).json(createErrorResponse('NOT_FOUND', `Connector '${req.params.type}' not found`));
      return;
    }

    const { action, params, config } = req.body;

    if (config) {
      await connector.initialize(config);
    }

    const result = await connector.execute(action, params || {});
    res.json(createSuccessResponse(result));
  } catch (error: any) {
    logger.error('Connector execution failed', { type: req.params.type, error: error.message });
    res.status(500).json(createErrorResponse('EXECUTION_FAILED', error.message));
  }
});

// Validate connector config
integrationRouter.post('/connectors/:type/validate', async (req: Request, res: Response) => {
  try {
    const connector = registry.get(req.params.type);
    if (!connector) {
      res.status(404).json(createErrorResponse('NOT_FOUND', `Connector '${req.params.type}' not found`));
      return;
    }

    const isValid = await connector.validateConfig(req.body);
    res.json(createSuccessResponse({ valid: isValid }));
  } catch (error: any) {
    res.status(400).json(createErrorResponse('VALIDATION_FAILED', error.message));
  }
});
