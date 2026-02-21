import { BaseEvent } from './base.event';
import { ExecutionStatus } from '../dtos/workflow.dto';

export const WORKFLOW_EVENTS = {
  WORKFLOW_CREATED: 'workflow.created',
  WORKFLOW_UPDATED: 'workflow.updated',
  WORKFLOW_DELETED: 'workflow.deleted',
  WORKFLOW_ACTIVATED: 'workflow.activated',
  WORKFLOW_DEACTIVATED: 'workflow.deactivated',
  EXECUTION_STARTED: 'workflow.execution.started',
  EXECUTION_COMPLETED: 'workflow.execution.completed',
  EXECUTION_FAILED: 'workflow.execution.failed',
  EXECUTION_CANCELLED: 'workflow.execution.cancelled',
  STEP_STARTED: 'workflow.step.started',
  STEP_COMPLETED: 'workflow.step.completed',
  STEP_FAILED: 'workflow.step.failed',
} as const;

export type WorkflowEventType = (typeof WORKFLOW_EVENTS)[keyof typeof WORKFLOW_EVENTS];

export interface WorkflowCreatedEvent extends BaseEvent {
  eventType: typeof WORKFLOW_EVENTS.WORKFLOW_CREATED;
  payload: {
    workflowId: string;
    name: string;
    tenantId: string;
    createdBy: string;
  };
}

export interface ExecutionStartedEvent extends BaseEvent {
  eventType: typeof WORKFLOW_EVENTS.EXECUTION_STARTED;
  payload: {
    executionId: string;
    workflowId: string;
    workflowName: string;
    tenantId: string;
    triggeredBy: string;
    triggerPayload?: Record<string, unknown>;
  };
}

export interface ExecutionCompletedEvent extends BaseEvent {
  eventType: typeof WORKFLOW_EVENTS.EXECUTION_COMPLETED;
  payload: {
    executionId: string;
    workflowId: string;
    workflowName: string;
    tenantId: string;
    status: ExecutionStatus;
    durationMs: number;
    stepsExecuted: number;
  };
}

export interface ExecutionFailedEvent extends BaseEvent {
  eventType: typeof WORKFLOW_EVENTS.EXECUTION_FAILED;
  payload: {
    executionId: string;
    workflowId: string;
    workflowName: string;
    tenantId: string;
    error: string;
    failedStepId?: string;
    durationMs: number;
  };
}

export interface StepFailedEvent extends BaseEvent {
  eventType: typeof WORKFLOW_EVENTS.STEP_FAILED;
  payload: {
    executionId: string;
    workflowId: string;
    stepId: string;
    stepName: string;
    tenantId: string;
    error: string;
    attempt: number;
  };
}
