import { prisma } from '../database/prisma';
import { EmailSender } from '../senders/email.sender';
import { Logger } from '../utils/logger';
import { NotFoundError, ForbiddenError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';
import { publishEvent } from '../messaging/rabbitmq';
import Handlebars from 'handlebars';

const logger = new Logger('notification-service:notification');
const emailSender = new EmailSender();

export class NotificationService {
  async list(
    tenantId: string,
    options: { page: number; limit: number; channel?: string; status?: string }
  ) {
    const { page, limit, channel, status } = options;
    const skip = (page - 1) * limit;
    const where = { tenantId, ...(channel && { channel }), ...(status && { status }) };

    const [notifications, total] = await Promise.all([
      prisma.notification.findMany({ where, skip, take: limit, orderBy: { createdAt: 'desc' } }),
      prisma.notification.count({ where }),
    ]);

    return { data: notifications, meta: createPaginationMeta(total, page, limit) };
  }

  async getById(id: string, tenantId: string) {
    const notification = await prisma.notification.findUnique({ where: { id } });
    if (!notification) throw new NotFoundError('Notification', id);
    if (notification.tenantId !== tenantId) throw new ForbiddenError();
    return notification;
  }

  async send(dto: {
    type: string;
    channel: string;
    recipient: string;
    subject?: string;
    body: string;
    templateId?: string;
    templateData?: Record<string, unknown>;
    tenantId: string;
    metadata?: Record<string, unknown>;
  }) {
    let body = dto.body;
    let subject = dto.subject;

    // Render template if provided
    if (dto.templateId) {
      const template = await prisma.notificationTemplate.findUnique({
        where: { id: dto.templateId },
      });
      if (template) {
        const compiledBody = Handlebars.compile(template.body);
        body = compiledBody(dto.templateData ?? {});
        if (template.subject) {
          const compiledSubject = Handlebars.compile(template.subject);
          subject = compiledSubject(dto.templateData ?? {});
        }
      }
    }

    const notification = await prisma.notification.create({
      data: {
        type: dto.type,
        channel: dto.channel,
        recipient: dto.recipient,
        subject,
        body,
        tenantId: dto.tenantId,
        templateId: dto.templateId,
        templateData: (dto.templateData ?? {}) as object,
        metadata: (dto.metadata ?? {}) as object,
        status: 'PENDING',
      },
    });

    // Send asynchronously
    this.deliver(notification).catch((err) =>
      logger.error('Delivery failed', err, { notificationId: notification.id })
    );

    return notification;
  }

  private async deliver(notification: {
    id: string;
    channel: string;
    recipient: string;
    subject?: string | null;
    body: string;
    tenantId: string;
  }): Promise<void> {
    try {
      switch (notification.channel.toUpperCase()) {
        case 'EMAIL':
          await emailSender.send({
            to: notification.recipient,
            subject: notification.subject ?? 'Notification',
            html: notification.body,
          });
          break;
        default:
          logger.warn('Unsupported notification channel', { channel: notification.channel });
          return;
      }

      await prisma.notification.update({
        where: { id: notification.id },
        data: { status: 'SENT', sentAt: new Date() },
      });

      await publishEvent('notification.sent', {
        notificationId: notification.id,
        channel: notification.channel,
        recipient: notification.recipient,
        tenantId: notification.tenantId,
        sentAt: new Date().toISOString(),
      });

      logger.info('Notification sent', { notificationId: notification.id });
    } catch (error) {
      const errMsg = (error as Error).message;

      await prisma.notification.update({
        where: { id: notification.id },
        data: { status: 'FAILED', failedAt: new Date(), error: errMsg },
      });

      await publishEvent('notification.failed', {
        notificationId: notification.id,
        channel: notification.channel,
        recipient: notification.recipient,
        tenantId: notification.tenantId,
        error: errMsg,
        failedAt: new Date().toISOString(),
      });
    }
  }
}
