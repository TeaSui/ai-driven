export interface IWorkflow {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  steps: IWorkflowStep[];
  triggers: IWorkflowTrigger[];
  status: WorkflowStatus;
  version: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface IWorkflowStep {
  id: string;
  type: StepType;
  name: string;
  config: Record<string, unknown>;
  nextSteps: string[];
  conditions?: IStepCondition[];
  retryPolicy?: IRetryPolicy;
}

export interface IWorkflowTrigger {
  type: TriggerType;
  config: Record<string, unknown>;
}

export interface IStepCondition {
  field: string;
  operator: 'eq' | 'neq' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'in';
  value: unknown;
}

export interface IRetryPolicy {
  maxRetries: number;
  backoffMs: number;
  backoffMultiplier: number;
}

export interface IWorkflowExecution {
  id: string;
  workflowId: string;
  tenantId: string;
  status: ExecutionStatus;
  currentStepId: string | null;
  context: Record<string, unknown>;
  startedAt: Date;
  completedAt: Date | null;
  error: string | null;
}

export enum WorkflowStatus {
  DRAFT = 'draft',
  ACTIVE = 'active',
  PAUSED = 'paused',
  ARCHIVED = 'archived',
}

export enum StepType {
  ACTION = 'action',
  CONDITION = 'condition',
  INTEGRATION = 'integration',
  NOTIFICATION = 'notification',
  DELAY = 'delay',
  TRANSFORM = 'transform',
}

export enum TriggerType {
  MANUAL = 'manual',
  SCHEDULE = 'schedule',
  WEBHOOK = 'webhook',
  EVENT = 'event',
}

export enum ExecutionStatus {
  PENDING = 'pending',
  RUNNING = 'running',
  COMPLETED = 'completed',
  FAILED = 'failed',
  CANCELLED = 'cancelled',
}
