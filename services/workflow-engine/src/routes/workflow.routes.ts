import { Router, Request, Response } from 'express';
import { WorkflowService } from '../services/workflow.service';
import { createSuccessResponse, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('workflow-engine:routes');
const workflowService = new WorkflowService();
export const workflowRouter = Router();

// Create workflow
workflowRouter.post('/', async (req: Request, res: Response) => {
  try {
    const tenantId = req.headers['x-tenant-id'] as string;
    if (!tenantId) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'x-tenant-id header is required'));
      return;
    }

    const workflow = await workflowService.create(tenantId, req.body);
    res.status(201).json(createSuccessResponse(workflow));
  } catch (error: any) {
    logger.error('Failed to create workflow', { error: error.message });
    res.status(400).json(createErrorResponse('CREATE_FAILED', error.message));
  }
});

// List workflows for tenant
workflowRouter.get('/', async (req: Request, res: Response) => {
  try {
    const tenantId = req.headers['x-tenant-id'] as string;
    if (!tenantId) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'x-tenant-id header is required'));
      return;
    }

    const workflows = await workflowService.listByTenant(tenantId);
    res.json(createSuccessResponse(workflows));
  } catch (error: any) {
    logger.error('Failed to list workflows', { error: error.message });
    res.status(500).json(createErrorResponse('LIST_FAILED', error.message));
  }
});

// Get workflow by ID
workflowRouter.get('/:id', async (req: Request, res: Response) => {
  try {
    const workflow = await workflowService.getById(req.params.id);
    if (!workflow) {
      res.status(404).json(createErrorResponse('NOT_FOUND', 'Workflow not found'));
      return;
    }
    res.json(createSuccessResponse(workflow));
  } catch (error: any) {
    logger.error('Failed to get workflow', { error: error.message });
    res.status(500).json(createErrorResponse('GET_FAILED', error.message));
  }
});

// Execute workflow
workflowRouter.post('/:id/execute', async (req: Request, res: Response) => {
  try {
    const tenantId = req.headers['x-tenant-id'] as string;
    if (!tenantId) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'x-tenant-id header is required'));
      return;
    }

    const execution = await workflowService.execute(req.params.id, tenantId, req.body.input);
    res.json(createSuccessResponse(execution));
  } catch (error: any) {
    logger.error('Failed to execute workflow', { error: error.message });
    res.status(400).json(createErrorResponse('EXECUTION_FAILED', error.message));
  }
});

// Update workflow
workflowRouter.put('/:id', async (req: Request, res: Response) => {
  try {
    const workflow = await workflowService.update(req.params.id, req.body);
    res.json(createSuccessResponse(workflow));
  } catch (error: any) {
    logger.error('Failed to update workflow', { error: error.message });
    res.status(400).json(createErrorResponse('UPDATE_FAILED', error.message));
  }
});

// Delete workflow
workflowRouter.delete('/:id', async (req: Request, res: Response) => {
  try {
    await workflowService.delete(req.params.id);
    res.json(createSuccessResponse({ deleted: true }));
  } catch (error: any) {
    logger.error('Failed to delete workflow', { error: error.message });
    res.status(400).json(createErrorResponse('DELETE_FAILED', error.message));
  }
});
