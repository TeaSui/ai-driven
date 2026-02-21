export interface CreateWorkflowDto {
  name: string;
  description?: string;
  trigger: WorkflowTriggerDto;
  steps: WorkflowStepDto[];
  isActive?: boolean;
  tags?: string[];
}

export interface UpdateWorkflowDto {
  name?: string;
  description?: string;
  trigger?: WorkflowTriggerDto;
  steps?: WorkflowStepDto[];
  isActive?: boolean;
  tags?: string[];
}

export interface WorkflowDto {
  id: string;
  name: string;
  description?: string;
  trigger: WorkflowTriggerDto;
  steps: WorkflowStepDto[];
  isActive: boolean;
  tags: string[];
  tenantId: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  executionCount: number;
  lastExecutedAt?: string;
}

export interface WorkflowTriggerDto {
  type: TriggerType;
  config: Record<string, unknown>;
  schedule?: string; // cron expression for scheduled triggers
}

export interface WorkflowStepDto {
  id: string;
  name: string;
  type: StepType;
  integrationId?: string;
  action: string;
  config: Record<string, unknown>;
  conditions?: StepConditionDto[];
  onSuccess?: string; // next step id
  onFailure?: string; // next step id or 'stop'
  retryPolicy?: RetryPolicyDto;
}

export interface StepConditionDto {
  field: string;
  operator: ConditionOperator;
  value: unknown;
}

export interface RetryPolicyDto {
  maxAttempts: number;
  backoffMs: number;
  backoffMultiplier?: number;
}

export interface WorkflowExecutionDto {
  id: string;
  workflowId: string;
  status: ExecutionStatus;
  triggeredBy: string;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  stepResults: StepResultDto[];
  error?: string;
  tenantId: string;
}

export interface StepResultDto {
  stepId: string;
  stepName: string;
  status: ExecutionStatus;
  startedAt: string;
  completedAt?: string;
  output?: Record<string, unknown>;
  error?: string;
  attempt: number;
}

export interface TriggerWorkflowDto {
  workflowId: string;
  payload?: Record<string, unknown>;
  triggeredBy: string;
}

export enum TriggerType {
  MANUAL = 'MANUAL',
  SCHEDULED = 'SCHEDULED',
  WEBHOOK = 'WEBHOOK',
  EVENT = 'EVENT',
}

export enum StepType {
  ACTION = 'ACTION',
  CONDITION = 'CONDITION',
  DELAY = 'DELAY',
  LOOP = 'LOOP',
  PARALLEL = 'PARALLEL',
}

export enum ConditionOperator {
  EQUALS = 'EQUALS',
  NOT_EQUALS = 'NOT_EQUALS',
  GREATER_THAN = 'GREATER_THAN',
  LESS_THAN = 'LESS_THAN',
  CONTAINS = 'CONTAINS',
  NOT_CONTAINS = 'NOT_CONTAINS',
  IS_NULL = 'IS_NULL',
  IS_NOT_NULL = 'IS_NOT_NULL',
}

export enum ExecutionStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  SUCCESS = 'SUCCESS',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED',
  SKIPPED = 'SKIPPED',
}
