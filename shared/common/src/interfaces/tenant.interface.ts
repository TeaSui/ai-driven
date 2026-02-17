export interface ITenant {
  id: string;
  name: string;
  slug: string;
  config: ITenantConfig;
  status: TenantStatus;
  createdAt: Date;
  updatedAt: Date;
}

export interface ITenantConfig {
  enabledModules: string[];
  integrations: Record<string, IIntegrationConfig>;
  customSettings: Record<string, unknown>;
  maxWorkflows: number;
  maxUsers: number;
}

export interface IIntegrationConfig {
  type: string;
  credentials: Record<string, string>;
  settings: Record<string, unknown>;
  enabled: boolean;
}

export enum TenantStatus {
  ACTIVE = 'active',
  INACTIVE = 'inactive',
  SUSPENDED = 'suspended',
  TRIAL = 'trial',
}
