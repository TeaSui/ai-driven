import { Request, Response } from 'express';
import { NotificationService } from '../services/notification.service';

const notificationService = new NotificationService();

export class NotificationController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20, channel, status } = req.query;
    const result = await notificationService.list(tenantId, {
      page: Number(page),
      limit: Number(limit),
      channel: channel as string | undefined,
      status: status as string | undefined,
    });
    res.json({ success: true, ...result });
  };

  send = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const notification = await notificationService.send({ ...req.body, tenantId });
    res.status(202).json({ success: true, data: notification });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const notification = await notificationService.getById(req.params.id, tenantId);
    res.json({ success: true, data: notification });
  };
}
