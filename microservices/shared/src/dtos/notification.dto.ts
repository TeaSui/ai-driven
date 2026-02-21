export interface SendNotificationDto {
  type: NotificationType;
  channel: NotificationChannel;
  recipient: string;
  subject?: string;
  body: string;
  templateId?: string;
  templateData?: Record<string, unknown>;
  tenantId: string;
  metadata?: Record<string, unknown>;
}

export interface NotificationDto {
  id: string;
  type: NotificationType;
  channel: NotificationChannel;
  recipient: string;
  subject?: string;
  body: string;
  status: NotificationStatus;
  tenantId: string;
  sentAt?: string;
  failedAt?: string;
  error?: string;
  createdAt: string;
  metadata?: Record<string, unknown>;
}

export interface CreateTemplateDto {
  name: string;
  channel: NotificationChannel;
  subject?: string;
  body: string;
  variables: string[];
  tenantId: string;
}

export interface NotificationTemplateDto {
  id: string;
  name: string;
  channel: NotificationChannel;
  subject?: string;
  body: string;
  variables: string[];
  tenantId: string;
  createdAt: string;
  updatedAt: string;
}

export enum NotificationType {
  WORKFLOW_STARTED = 'WORKFLOW_STARTED',
  WORKFLOW_COMPLETED = 'WORKFLOW_COMPLETED',
  WORKFLOW_FAILED = 'WORKFLOW_FAILED',
  STEP_FAILED = 'STEP_FAILED',
  INTEGRATION_ERROR = 'INTEGRATION_ERROR',
  SYSTEM_ALERT = 'SYSTEM_ALERT',
  CUSTOM = 'CUSTOM',
}

export enum NotificationChannel {
  EMAIL = 'EMAIL',
  SLACK = 'SLACK',
  WEBHOOK = 'WEBHOOK',
  IN_APP = 'IN_APP',
}

export enum NotificationStatus {
  PENDING = 'PENDING',
  SENT = 'SENT',
  FAILED = 'FAILED',
  DELIVERED = 'DELIVERED',
}
