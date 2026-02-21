export interface CreateIntegrationDto {
  name: string;
  type: IntegrationType;
  config: Record<string, unknown>;
  credentials?: Record<string, string>;
  isActive?: boolean;
}

export interface UpdateIntegrationDto {
  name?: string;
  config?: Record<string, unknown>;
  credentials?: Record<string, string>;
  isActive?: boolean;
}

export interface IntegrationDto {
  id: string;
  name: string;
  type: IntegrationType;
  config: Record<string, unknown>;
  isActive: boolean;
  tenantId: string;
  createdAt: string;
  updatedAt: string;
  lastTestedAt?: string;
  status: IntegrationStatus;
}

export interface TestIntegrationDto {
  integrationId: string;
}

export interface IntegrationTestResultDto {
  success: boolean;
  message: string;
  latencyMs?: number;
  testedAt: string;
}

export interface ExecuteIntegrationActionDto {
  integrationId: string;
  action: string;
  params: Record<string, unknown>;
  workflowExecutionId?: string;
  stepId?: string;
}

export interface IntegrationActionResultDto {
  success: boolean;
  data?: Record<string, unknown>;
  error?: string;
  executedAt: string;
  durationMs: number;
}

export enum IntegrationType {
  SLACK = 'SLACK',
  JIRA = 'JIRA',
  GITHUB = 'GITHUB',
  GITLAB = 'GITLAB',
  TRELLO = 'TRELLO',
  ASANA = 'ASANA',
  SALESFORCE = 'SALESFORCE',
  HUBSPOT = 'HUBSPOT',
  ZENDESK = 'ZENDESK',
  GOOGLE_SHEETS = 'GOOGLE_SHEETS',
  NOTION = 'NOTION',
  WEBHOOK = 'WEBHOOK',
  CUSTOM = 'CUSTOM',
}

export enum IntegrationStatus {
  CONNECTED = 'CONNECTED',
  DISCONNECTED = 'DISCONNECTED',
  ERROR = 'ERROR',
  PENDING = 'PENDING',
}
