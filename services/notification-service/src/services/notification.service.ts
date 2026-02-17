import {
  INotification,
  NotificationChannel,
  NotificationStatus,
  SendNotificationDto,
  createLogger,
} from '@ai-driven/common';

const logger = createLogger('notification-service:service');

// In-memory store — replace with database in production
const notifications: Map<string, INotification> = new Map();

export class NotificationService {
  async send(tenantId: string, dto: SendNotificationDto): Promise<INotification> {
    const notification: INotification = {
      id: `notif_${Date.now()}`,
      tenantId,
      channel: dto.channel,
      recipient: dto.recipient,
      subject: dto.subject,
      body: dto.body,
      metadata: dto.metadata,
      status: NotificationStatus.PENDING,
      createdAt: new Date(),
    };

    notifications.set(notification.id, notification);
    logger.info('Notification queued', { id: notification.id, channel: dto.channel, tenantId });

    // Process async
    this.processNotification(notification).catch((err) => {
      logger.error('Notification processing failed', { id: notification.id, error: err.message });
    });

    return notification;
  }

  async getById(id: string): Promise<INotification | undefined> {
    return notifications.get(id);
  }

  async listByTenant(tenantId: string): Promise<INotification[]> {
    return Array.from(notifications.values()).filter((n) => n.tenantId === tenantId);
  }

  private async processNotification(notification: INotification): Promise<void> {
    try {
      switch (notification.channel) {
        case NotificationChannel.EMAIL:
          logger.info('Sending email notification', { to: notification.recipient });
          break;
        case NotificationChannel.SLACK:
          logger.info('Sending Slack notification', { to: notification.recipient });
          break;
        case NotificationChannel.WEBHOOK:
          logger.info('Sending webhook notification', { to: notification.recipient });
          break;
        default:
          logger.warn('Unknown notification channel', { channel: notification.channel });
      }

      // Simulate sending
      await new Promise((resolve) => setTimeout(resolve, 50));

      notification.status = NotificationStatus.SENT;
      notification.sentAt = new Date();
      notifications.set(notification.id, notification);

      logger.info('Notification sent', { id: notification.id });
    } catch (error: any) {
      notification.status = NotificationStatus.FAILED;
      notifications.set(notification.id, notification);
      logger.error('Notification failed', { id: notification.id, error: error.message });
    }
  }
}
