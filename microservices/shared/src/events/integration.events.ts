import { BaseEvent } from './base.event';
import { IntegrationType } from '../dtos/integration.dto';

export const INTEGRATION_EVENTS = {
  INTEGRATION_CREATED: 'integration.created',
  INTEGRATION_UPDATED: 'integration.updated',
  INTEGRATION_DELETED: 'integration.deleted',
  INTEGRATION_CONNECTED: 'integration.connected',
  INTEGRATION_DISCONNECTED: 'integration.disconnected',
  INTEGRATION_ERROR: 'integration.error',
  ACTION_EXECUTED: 'integration.action.executed',
  ACTION_FAILED: 'integration.action.failed',
} as const;

export type IntegrationEventType = (typeof INTEGRATION_EVENTS)[keyof typeof INTEGRATION_EVENTS];

export interface IntegrationCreatedEvent extends BaseEvent {
  eventType: typeof INTEGRATION_EVENTS.INTEGRATION_CREATED;
  payload: {
    integrationId: string;
    name: string;
    type: IntegrationType;
    tenantId: string;
  };
}

export interface IntegrationErrorEvent extends BaseEvent {
  eventType: typeof INTEGRATION_EVENTS.INTEGRATION_ERROR;
  payload: {
    integrationId: string;
    name: string;
    type: IntegrationType;
    tenantId: string;
    error: string;
    action?: string;
  };
}

export interface ActionExecutedEvent extends BaseEvent {
  eventType: typeof INTEGRATION_EVENTS.ACTION_EXECUTED;
  payload: {
    integrationId: string;
    action: string;
    tenantId: string;
    workflowExecutionId?: string;
    stepId?: string;
    durationMs: number;
    success: boolean;
  };
}
