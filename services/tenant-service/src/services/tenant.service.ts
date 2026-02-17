import {
  ITenant,
  ITenantConfig,
  TenantStatus,
  CreateTenantDto,
  UpdateTenantDto,
  createLogger,
  isValidSlug,
} from '@ai-driven/common';

const logger = createLogger('tenant-service:service');

// In-memory store — replace with database in production
const tenants: Map<string, ITenant> = new Map();

const DEFAULT_CONFIG: ITenantConfig = {
  enabledModules: ['workflow-engine', 'notification-service'],
  integrations: {},
  customSettings: {},
  maxWorkflows: 10,
  maxUsers: 5,
};

export class TenantService {
  async create(dto: CreateTenantDto): Promise<ITenant> {
    if (!isValidSlug(dto.slug)) {
      throw new Error('Invalid slug format. Use lowercase letters, numbers, and hyphens only.');
    }

    // Check for duplicate slug
    const existing = Array.from(tenants.values()).find((t) => t.slug === dto.slug);
    if (existing) {
      throw new Error(`Tenant with slug '${dto.slug}' already exists`);
    }

    const tenant: ITenant = {
      id: `tenant_${Date.now()}`,
      name: dto.name,
      slug: dto.slug,
      config: { ...DEFAULT_CONFIG, ...dto.config },
      status: TenantStatus.TRIAL,
      createdAt: new Date(),
      updatedAt: new Date(),
    };

    tenants.set(tenant.id, tenant);
    logger.info('Tenant created', { tenantId: tenant.id, slug: tenant.slug });
    return tenant;
  }

  async getById(id: string): Promise<ITenant | undefined> {
    return tenants.get(id);
  }

  async getBySlug(slug: string): Promise<ITenant | undefined> {
    return Array.from(tenants.values()).find((t) => t.slug === slug);
  }

  async listAll(): Promise<ITenant[]> {
    return Array.from(tenants.values());
  }

  async update(id: string, dto: UpdateTenantDto): Promise<ITenant> {
    const tenant = tenants.get(id);
    if (!tenant) {
      throw new Error('Tenant not found');
    }

    const updated: ITenant = {
      ...tenant,
      name: dto.name || tenant.name,
      config: dto.config ? { ...tenant.config, ...dto.config } : tenant.config,
      updatedAt: new Date(),
    };

    tenants.set(id, updated);
    logger.info('Tenant updated', { tenantId: id });
    return updated;
  }

  async delete(id: string): Promise<void> {
    if (!tenants.has(id)) {
      throw new Error('Tenant not found');
    }
    tenants.delete(id);
    logger.info('Tenant deleted', { tenantId: id });
  }

  async updateModules(id: string, modules: string[]): Promise<ITenant> {
    const tenant = tenants.get(id);
    if (!tenant) {
      throw new Error('Tenant not found');
    }

    tenant.config.enabledModules = modules;
    tenant.updatedAt = new Date();
    tenants.set(id, tenant);

    logger.info('Tenant modules updated', { tenantId: id, modules });
    return tenant;
  }
}
