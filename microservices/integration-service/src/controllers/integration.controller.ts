import { Request, Response } from 'express';
import { IntegrationService } from '../services/integration.service';

const integrationService = new IntegrationService();

export class IntegrationController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20, type } = req.query;
    const result = await integrationService.list(tenantId, {
      page: Number(page),
      limit: Number(limit),
      type: type as string | undefined,
    });
    res.json({ success: true, ...result });
  };

  create = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const integration = await integrationService.create(tenantId, req.body);
    res.status(201).json({ success: true, data: integration });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const integration = await integrationService.getById(req.params.id, tenantId);
    res.json({ success: true, data: integration });
  };

  update = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const integration = await integrationService.update(req.params.id, tenantId, req.body);
    res.json({ success: true, data: integration });
  };

  delete = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    await integrationService.delete(req.params.id, tenantId);
    res.status(204).send();
  };

  test = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const result = await integrationService.test(req.params.id, tenantId);
    res.json({ success: true, data: result });
  };

  execute = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const result = await integrationService.executeAction(tenantId, req.body);
    res.json({ success: true, data: result });
  };
}
