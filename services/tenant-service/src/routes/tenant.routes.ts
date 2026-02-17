import { Router, Request, Response } from 'express';
import { TenantService } from '../services/tenant.service';
import { createSuccessResponse, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('tenant-service:routes');
const tenantService = new TenantService();
export const tenantRouter = Router();

// Create tenant
tenantRouter.post('/', async (req: Request, res: Response) => {
  try {
    const tenant = await tenantService.create(req.body);
    res.status(201).json(createSuccessResponse(tenant));
  } catch (error: any) {
    logger.error('Failed to create tenant', { error: error.message });
    res.status(400).json(createErrorResponse('CREATE_FAILED', error.message));
  }
});

// List tenants
tenantRouter.get('/', async (_req: Request, res: Response) => {
  try {
    const tenants = await tenantService.listAll();
    res.json(createSuccessResponse(tenants));
  } catch (error: any) {
    logger.error('Failed to list tenants', { error: error.message });
    res.status(500).json(createErrorResponse('LIST_FAILED', error.message));
  }
});

// Get tenant by ID
tenantRouter.get('/:id', async (req: Request, res: Response) => {
  try {
    const tenant = await tenantService.getById(req.params.id);
    if (!tenant) {
      res.status(404).json(createErrorResponse('NOT_FOUND', 'Tenant not found'));
      return;
    }
    res.json(createSuccessResponse(tenant));
  } catch (error: any) {
    res.status(500).json(createErrorResponse('GET_FAILED', error.message));
  }
});

// Get tenant by slug
tenantRouter.get('/slug/:slug', async (req: Request, res: Response) => {
  try {
    const tenant = await tenantService.getBySlug(req.params.slug);
    if (!tenant) {
      res.status(404).json(createErrorResponse('NOT_FOUND', 'Tenant not found'));
      return;
    }
    res.json(createSuccessResponse(tenant));
  } catch (error: any) {
    res.status(500).json(createErrorResponse('GET_FAILED', error.message));
  }
});

// Update tenant
tenantRouter.put('/:id', async (req: Request, res: Response) => {
  try {
    const tenant = await tenantService.update(req.params.id, req.body);
    res.json(createSuccessResponse(tenant));
  } catch (error: any) {
    logger.error('Failed to update tenant', { error: error.message });
    res.status(400).json(createErrorResponse('UPDATE_FAILED', error.message));
  }
});

// Delete tenant
tenantRouter.delete('/:id', async (req: Request, res: Response) => {
  try {
    await tenantService.delete(req.params.id);
    res.json(createSuccessResponse({ deleted: true }));
  } catch (error: any) {
    logger.error('Failed to delete tenant', { error: error.message });
    res.status(400).json(createErrorResponse('DELETE_FAILED', error.message));
  }
});

// Update tenant modules
tenantRouter.put('/:id/modules', async (req: Request, res: Response) => {
  try {
    const tenant = await tenantService.updateModules(req.params.id, req.body.modules);
    res.json(createSuccessResponse(tenant));
  } catch (error: any) {
    logger.error('Failed to update tenant modules', { error: error.message });
    res.status(400).json(createErrorResponse('UPDATE_FAILED', error.message));
  }
});
