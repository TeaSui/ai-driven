import { Request, Response } from 'express';
import { ExecutionService } from '../services/execution.service';

const executionService = new ExecutionService();

export class ExecutionController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20, workflowId, status } = req.query;
    const result = await executionService.list(tenantId, {
      page: Number(page),
      limit: Number(limit),
      workflowId: workflowId as string | undefined,
      status: status as string | undefined,
    });
    res.json({ success: true, ...result });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const execution = await executionService.getById(req.params.id, tenantId);
    res.json({ success: true, data: execution });
  };

  cancel = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    await executionService.cancel(req.params.id, tenantId);
    res.json({ success: true, message: 'Execution cancelled' });
  };
}
