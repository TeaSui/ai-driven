import { Router, Request, Response } from 'express';
import { NotificationService } from '../services/notification.service';
import { createSuccessResponse, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('notification-service:routes');
const notificationService = new NotificationService();
export const notificationRouter = Router();

// Send notification
notificationRouter.post('/send', async (req: Request, res: Response) => {
  try {
    const tenantId = req.headers['x-tenant-id'] as string;
    if (!tenantId) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'x-tenant-id header is required'));
      return;
    }

    const result = await notificationService.send(tenantId, req.body);
    res.json(createSuccessResponse(result));
  } catch (error: any) {
    logger.error('Failed to send notification', { error: error.message });
    res.status(500).json(createErrorResponse('SEND_FAILED', error.message));
  }
});

// List notifications for tenant
notificationRouter.get('/', async (req: Request, res: Response) => {
  try {
    const tenantId = req.headers['x-tenant-id'] as string;
    if (!tenantId) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'x-tenant-id header is required'));
      return;
    }

    const notifications = await notificationService.listByTenant(tenantId);
    res.json(createSuccessResponse(notifications));
  } catch (error: any) {
    logger.error('Failed to list notifications', { error: error.message });
    res.status(500).json(createErrorResponse('LIST_FAILED', error.message));
  }
});

// Get notification by ID
notificationRouter.get('/:id', async (req: Request, res: Response) => {
  try {
    const notification = await notificationService.getById(req.params.id);
    if (!notification) {
      res.status(404).json(createErrorResponse('NOT_FOUND', 'Notification not found'));
      return;
    }
    res.json(createSuccessResponse(notification));
  } catch (error: any) {
    res.status(500).json(createErrorResponse('GET_FAILED', error.message));
  }
});
