import { ITenantConfig } from '../interfaces/tenant.interface';

export interface CreateTenantDto {
  name: string;
  slug: string;
  config?: Partial<ITenantConfig>;
}

export interface UpdateTenantDto {
  name?: string;
  config?: Partial<ITenantConfig>;
}

export interface TenantQueryDto {
  page?: number;
  limit?: number;
  status?: string;
  search?: string;
}
