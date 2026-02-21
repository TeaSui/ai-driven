import { prisma } from '../database/prisma';
import { ConnectorFactory } from '../connectors/connector.factory';
import { Logger } from '../utils/logger';
import { NotFoundError, ForbiddenError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';
import { encrypt, decrypt } from '../utils/crypto';
import { publishEvent } from '../messaging/rabbitmq';

const logger = new Logger('integration-service:integration');
const ENCRYPTION_KEY = process.env.ENCRYPTION_KEY || 'fallback-key-32-chars-long-here!';

export class IntegrationService {
  async list(
    tenantId: string,
    options: { page: number; limit: number; type?: string }
  ) {
    const { page, limit, type } = options;
    const skip = (page - 1) * limit;
    const where = { tenantId, ...(type && { type }) };

    const [integrations, total] = await Promise.all([
      prisma.integration.findMany({
        where,
        skip,
        take: limit,
        orderBy: { createdAt: 'desc' },
        select: {
          id: true, name: true, type: true, config: true,
          isActive: true, status: true, tenantId: true,
          lastTestedAt: true, createdAt: true, updatedAt: true,
        },
      }),
      prisma.integration.count({ where }),
    ]);

    return { data: integrations, meta: createPaginationMeta(total, page, limit) };
  }

  async create(
    tenantId: string,
    dto: {
      name: string;
      type: string;
      config: Record<string, unknown>;
      credentials?: Record<string, string>;
      isActive?: boolean;
    }
  ) {
    const encryptedCreds = dto.credentials
      ? encrypt(JSON.stringify(dto.credentials), ENCRYPTION_KEY)
      : null;

    const integration = await prisma.integration.create({
      data: {
        name: dto.name,
        type: dto.type,
        config: dto.config as object,
        encryptedCreds,
        isActive: dto.isActive ?? true,
        tenantId,
        status: 'PENDING',
      },
    });

    await publishEvent('integration.created', {
      integrationId: integration.id,
      name: integration.name,
      type: integration.type,
      tenantId,
    });

    logger.info('Integration created', { integrationId: integration.id, type: integration.type });
    return this.sanitize(integration);
  }

  async getById(id: string, tenantId: string) {
    const integration = await prisma.integration.findUnique({ where: { id } });
    if (!integration) throw new NotFoundError('Integration', id);
    if (integration.tenantId !== tenantId) throw new ForbiddenError();
    return this.sanitize(integration);
  }

  async update(
    id: string,
    tenantId: string,
    dto: {
      name?: string;
      config?: Record<string, unknown>;
      credentials?: Record<string, string>;
      isActive?: boolean;
    }
  ) {
    const integration = await prisma.integration.findUnique({ where: { id } });
    if (!integration) throw new NotFoundError('Integration', id);
    if (integration.tenantId !== tenantId) throw new ForbiddenError();

    const encryptedCreds = dto.credentials
      ? encrypt(JSON.stringify(dto.credentials), ENCRYPTION_KEY)
      : undefined;

    const updated = await prisma.integration.update({
      where: { id },
      data: {
        ...(dto.name && { name: dto.name }),
        ...(dto.config && { config: dto.config as object }),
        ...(encryptedCreds !== undefined && { encryptedCreds }),
        ...(dto.isActive !== undefined && { isActive: dto.isActive }),
      },
    });

    return this.sanitize(updated);
  }

  async delete(id: string, tenantId: string): Promise<void> {
    const integration = await prisma.integration.findUnique({ where: { id } });
    if (!integration) throw new NotFoundError('Integration', id);
    if (integration.tenantId !== tenantId) throw new ForbiddenError();
    await prisma.integration.delete({ where: { id } });
  }

  async test(id: string, tenantId: string) {
    const integration = await prisma.integration.findUnique({ where: { id } });
    if (!integration) throw new NotFoundError('Integration', id);
    if (integration.tenantId !== tenantId) throw new ForbiddenError();

    const credentials = integration.encryptedCreds
      ? JSON.parse(decrypt(integration.encryptedCreds, ENCRYPTION_KEY))
      : {};

    const connector = ConnectorFactory.create(
      integration.type,
      integration.config as Record<string, unknown>,
      credentials
    );

    const startTime = Date.now();
    const result = await connector.test();
    const latencyMs = Date.now() - startTime;

    await prisma.integration.update({
      where: { id },
      data: {
        lastTestedAt: new Date(),
        status: result.success ? 'CONNECTED' : 'ERROR',
      },
    });

    return { ...result, latencyMs, testedAt: new Date().toISOString() };
  }

  async executeAction(
    tenantId: string,
    dto: {
      integrationId: string;
      action: string;
      params: Record<string, unknown>;
      workflowExecutionId?: string;
      stepId?: string;
    }
  ) {
    const integration = await prisma.integration.findUnique({ where: { id: dto.integrationId } });
    if (!integration) throw new NotFoundError('Integration', dto.integrationId);
    if (integration.tenantId !== tenantId) throw new ForbiddenError();

    const credentials = integration.encryptedCreds
      ? JSON.parse(decrypt(integration.encryptedCreds, ENCRYPTION_KEY))
      : {};

    const connector = ConnectorFactory.create(
      integration.type,
      integration.config as Record<string, unknown>,
      credentials
    );

    const startTime = Date.now();
    let success = false;
    let result: Record<string, unknown> = {};
    let error: string | undefined;

    try {
      result = await connector.execute(dto.action, dto.params);
      success = true;
    } catch (err) {
      error = (err as Error).message;
      await publishEvent('integration.error', {
        integrationId: integration.id,
        name: integration.name,
        type: integration.type,
        tenantId,
        error,
        action: dto.action,
      });
    }

    const durationMs = Date.now() - startTime;

    await prisma.integrationActionLog.create({
      data: {
        integrationId: integration.id,
        action: dto.action,
        params: dto.params as object,
        result: result as object,
        success,
        error,
        durationMs,
        workflowExecutionId: dto.workflowExecutionId,
        stepId: dto.stepId,
        tenantId,
      },
    });

    await publishEvent('integration.action.executed', {
      integrationId: integration.id,
      action: dto.action,
      tenantId,
      workflowExecutionId: dto.workflowExecutionId,
      stepId: dto.stepId,
      durationMs,
      success,
    });

    return { success, data: result, error, executedAt: new Date().toISOString(), durationMs };
  }

  private sanitize(integration: {
    id: string;
    name: string;
    type: string;
    config: unknown;
    encryptedCreds?: string | null;
    isActive: boolean;
    status: string;
    tenantId: string;
    lastTestedAt?: Date | null;
    createdAt: Date;
    updatedAt: Date;
  }) {
    const { encryptedCreds: _creds, ...safe } = integration;
    return {
      ...safe,
      lastTestedAt: safe.lastTestedAt?.toISOString() ?? null,
      createdAt: safe.createdAt.toISOString(),
      updatedAt: safe.updatedAt.toISOString(),
    };
  }
}
