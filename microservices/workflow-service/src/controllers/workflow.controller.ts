import { Request, Response } from 'express';
import { WorkflowService } from '../services/workflow.service';

const workflowService = new WorkflowService();

export class WorkflowController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20, search, isActive } = req.query;
    const result = await workflowService.list(tenantId, {
      page: Number(page),
      limit: Number(limit),
      search: search as string | undefined,
      isActive: isActive !== undefined ? isActive === 'true' : undefined,
    });
    res.json({ success: true, ...result });
  };

  create = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const createdBy = req.headers['x-user-id'] as string;
    const workflow = await workflowService.create(tenantId, createdBy, req.body);
    res.status(201).json({ success: true, data: workflow });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const workflow = await workflowService.getById(req.params.id, tenantId);
    res.json({ success: true, data: workflow });
  };

  update = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const workflow = await workflowService.update(req.params.id, tenantId, req.body);
    res.json({ success: true, data: workflow });
  };

  delete = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    await workflowService.delete(req.params.id, tenantId);
    res.status(204).send();
  };

  trigger = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const triggeredBy = req.headers['x-user-id'] as string;
    const execution = await workflowService.trigger(
      req.params.id,
      tenantId,
      triggeredBy,
      req.body.payload
    );
    res.status(202).json({ success: true, data: execution });
  };

  activate = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const workflow = await workflowService.setActive(req.params.id, tenantId, true);
    res.json({ success: true, data: workflow });
  };

  deactivate = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const workflow = await workflowService.setActive(req.params.id, tenantId, false);
    res.json({ success: true, data: workflow });
  };
}
