import { Request, Response } from 'express';
import { TemplateService } from '../services/template.service';

const templateService = new TemplateService();

export class TemplateController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20 } = req.query;
    const result = await templateService.list(tenantId, { page: Number(page), limit: Number(limit) });
    res.json({ success: true, ...result });
  };

  create = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const template = await templateService.create({ ...req.body, tenantId });
    res.status(201).json({ success: true, data: template });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const template = await templateService.getById(req.params.id, tenantId);
    res.json({ success: true, data: template });
  };

  update = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const template = await templateService.update(req.params.id, tenantId, req.body);
    res.json({ success: true, data: template });
  };

  delete = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    await templateService.delete(req.params.id, tenantId);
    res.status(204).send();
  };
}
