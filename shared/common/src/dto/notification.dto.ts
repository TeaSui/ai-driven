import { NotificationChannel } from '../interfaces/notification.interface';

export interface SendNotificationDto {
  channel: NotificationChannel;
  recipient: string;
  subject: string;
  body: string;
  metadata?: Record<string, unknown>;
}

export interface NotificationQueryDto {
  page?: number;
  limit?: number;
  channel?: NotificationChannel;
  status?: string;
}
