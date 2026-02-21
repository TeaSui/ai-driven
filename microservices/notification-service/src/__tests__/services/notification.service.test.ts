import { NotificationService } from '../../services/notification.service';
import { prisma } from '../../database/prisma';
import * as rabbitmq from '../../messaging/rabbitmq';

jest.mock('../../database/prisma', () => ({
  prisma: {
    notification: {
      findMany: jest.fn(),
      findUnique: jest.fn(),
      create: jest.fn(),
      update: jest.fn(),
      count: jest.fn(),
    },
    notificationTemplate: {
      findUnique: jest.fn(),
    },
  },
}));

jest.mock('../../messaging/rabbitmq', () => ({
  publishEvent: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../../senders/email.sender', () => ({
  EmailSender: jest.fn().mockImplementation(() => ({
    send: jest.fn().mockResolvedValue(undefined),
  })),
}));

const mockNotification = {
  id: 'notif-123',
  type: 'WORKFLOW_FAILED',
  channel: 'EMAIL',
  recipient: 'user@example.com',
  subject: 'Test Subject',
  body: '<p>Test body</p>',
  status: 'PENDING',
  tenantId: 'tenant-123',
  templateId: null,
  templateData: {},
  metadata: {},
  sentAt: null,
  failedAt: null,
  error: null,
  createdAt: new Date(),
};

describe('NotificationService', () => {
  let service: NotificationService;

  beforeEach(() => {
    service = new NotificationService();
    jest.clearAllMocks();
  });

  describe('list', () => {
    it('should return paginated notifications', async () => {
      (prisma.notification.findMany as jest.Mock).mockResolvedValue([mockNotification]);
      (prisma.notification.count as jest.Mock).mockResolvedValue(1);

      const result = await service.list('tenant-123', { page: 1, limit: 10 });

      expect(result.data).toHaveLength(1);
      expect(result.meta.total).toBe(1);
    });
  });

  describe('send', () => {
    it('should create notification and trigger delivery', async () => {
      (prisma.notification.create as jest.Mock).mockResolvedValue(mockNotification);
      (prisma.notification.update as jest.Mock).mockResolvedValue({
        ...mockNotification,
        status: 'SENT',
      });

      const result = await service.send({
        type: 'CUSTOM',
        channel: 'EMAIL',
        recipient: 'user@example.com',
        subject: 'Hello',
        body: '<p>Hello World</p>',
        tenantId: 'tenant-123',
      });

      expect(result.id).toBe('notif-123');
      expect(prisma.notification.create).toHaveBeenCalled();
    });
  });

  describe('getById', () => {
    it('should return notification for correct tenant', async () => {
      (prisma.notification.findUnique as jest.Mock).mockResolvedValue(mockNotification);

      const result = await service.getById('notif-123', 'tenant-123');
      expect(result.id).toBe('notif-123');
    });

    it('should throw NotFoundError when notification does not exist', async () => {
      (prisma.notification.findUnique as jest.Mock).mockResolvedValue(null);

      await expect(service.getById('nonexistent', 'tenant-123')).rejects.toThrow('not found');
    });

    it('should throw ForbiddenError for wrong tenant', async () => {
      (prisma.notification.findUnique as jest.Mock).mockResolvedValue(mockNotification);

      await expect(service.getById('notif-123', 'wrong-tenant')).rejects.toThrow('Forbidden');
    });
  });
});
