import { BaseEvent } from './base.event';
import { NotificationChannel, NotificationType } from '../dtos/notification.dto';

export const NOTIFICATION_EVENTS = {
  NOTIFICATION_REQUESTED: 'notification.requested',
  NOTIFICATION_SENT: 'notification.sent',
  NOTIFICATION_FAILED: 'notification.failed',
  NOTIFICATION_DELIVERED: 'notification.delivered',
} as const;

export type NotificationEventType =
  (typeof NOTIFICATION_EVENTS)[keyof typeof NOTIFICATION_EVENTS];

export interface NotificationRequestedEvent extends BaseEvent {
  eventType: typeof NOTIFICATION_EVENTS.NOTIFICATION_REQUESTED;
  payload: {
    notificationId: string;
    type: NotificationType;
    channel: NotificationChannel;
    recipient: string;
    subject?: string;
    body: string;
    tenantId: string;
    templateId?: string;
    templateData?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
  };
}

export interface NotificationSentEvent extends BaseEvent {
  eventType: typeof NOTIFICATION_EVENTS.NOTIFICATION_SENT;
  payload: {
    notificationId: string;
    channel: NotificationChannel;
    recipient: string;
    tenantId: string;
    sentAt: string;
  };
}

export interface NotificationFailedEvent extends BaseEvent {
  eventType: typeof NOTIFICATION_EVENTS.NOTIFICATION_FAILED;
  payload: {
    notificationId: string;
    channel: NotificationChannel;
    recipient: string;
    tenantId: string;
    error: string;
    failedAt: string;
  };
}
