export interface INotification {
  id: string;
  tenantId: string;
  channel: NotificationChannel;
  recipient: string;
  subject: string;
  body: string;
  metadata?: Record<string, unknown>;
  status: NotificationStatus;
  sentAt?: Date;
  createdAt: Date;
}

export interface INotificationProvider {
  readonly channel: NotificationChannel;
  send(notification: INotification): Promise<INotificationResult>;
  validateConfig(config: Record<string, unknown>): Promise<boolean>;
}

export interface INotificationResult {
  success: boolean;
  messageId?: string;
  error?: string;
}

export enum NotificationChannel {
  EMAIL = 'email',
  SLACK = 'slack',
  WEBHOOK = 'webhook',
  SMS = 'sms',
  IN_APP = 'in_app',
}

export enum NotificationStatus {
  PENDING = 'pending',
  SENT = 'sent',
  DELIVERED = 'delivered',
  FAILED = 'failed',
}
