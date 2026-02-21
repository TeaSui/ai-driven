import { prisma } from '../database/prisma';
import { ExecutionEngine } from '../engine/execution.engine';
import { Logger } from '../utils/logger';
import { NotFoundError, ForbiddenError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';
import { publishEvent } from '../messaging/rabbitmq';

const logger = new Logger('workflow-service:workflow');
const engine = new ExecutionEngine();

export class WorkflowService {
  async list(
    tenantId: string,
    options: { page: number; limit: number; search?: string; isActive?: boolean }
  ) {
    const { page, limit, search, isActive } = options;
    const skip = (page - 1) * limit;

    const where = {
      tenantId,
      ...(isActive !== undefined && { isActive }),
      ...(search && {
        OR: [
          { name: { contains: search, mode: 'insensitive' as const } },
          { description: { contains: search, mode: 'insensitive' as const } },
        ],
      }),
    };

    const [workflows, total] = await Promise.all([
      prisma.workflow.findMany({
        where,
        skip,
        take: limit,
        orderBy: { updatedAt: 'desc' },
      }),
      prisma.workflow.count({ where }),
    ]);

    return { data: workflows, meta: createPaginationMeta(total, page, limit) };
  }

  async create(
    tenantId: string,
    createdBy: string,
    dto: {
      name: string;
      description?: string;
      trigger: { type: string; config: Record<string, unknown>; schedule?: string };
      steps: unknown[];
      isActive?: boolean;
      tags?: string[];
    }
  ) {
    const workflow = await prisma.workflow.create({
      data: {
        name: dto.name,
        description: dto.description,
        triggerType: dto.trigger.type,
        triggerConfig: dto.trigger.config as object,
        steps: dto.steps as object,
        isActive: dto.isActive ?? true,
        tags: dto.tags ?? [],
        tenantId,
        createdBy,
      },
    });

    logger.info('Workflow created', { workflowId: workflow.id, tenantId });

    await publishEvent('workflow.created', {
      workflowId: workflow.id,
      name: workflow.name,
      tenantId,
      createdBy,
    });

    return workflow;
  }

  async getById(id: string, tenantId: string) {
    const workflow = await prisma.workflow.findUnique({ where: { id } });
    if (!workflow) throw new NotFoundError('Workflow', id);
    if (workflow.tenantId !== tenantId) throw new ForbiddenError();
    return workflow;
  }

  async update(
    id: string,
    tenantId: string,
    dto: Partial<{
      name: string;
      description: string;
      trigger: { type: string; config: Record<string, unknown> };
      steps: unknown[];
      isActive: boolean;
      tags: string[];
    }>
  ) {
    const workflow = await this.getById(id, tenantId);

    const updated = await prisma.workflow.update({
      where: { id: workflow.id },
      data: {
        ...(dto.name && { name: dto.name }),
        ...(dto.description !== undefined && { description: dto.description }),
        ...(dto.trigger && {
          triggerType: dto.trigger.type,
          triggerConfig: dto.trigger.config as object,
        }),
        ...(dto.steps && { steps: dto.steps as object }),
        ...(dto.isActive !== undefined && { isActive: dto.isActive }),
        ...(dto.tags && { tags: dto.tags }),
      },
    });

    logger.info('Workflow updated', { workflowId: id });
    return updated;
  }

  async delete(id: string, tenantId: string): Promise<void> {
    const workflow = await this.getById(id, tenantId);
    await prisma.workflow.delete({ where: { id: workflow.id } });
    logger.info('Workflow deleted', { workflowId: id });
  }

  async trigger(
    id: string,
    tenantId: string,
    triggeredBy: string,
    payload?: Record<string, unknown>
  ) {
    const workflow = await this.getById(id, tenantId);

    if (!workflow.isActive) {
      throw new Error('Workflow is not active');
    }

    const execution = await engine.startExecution(workflow, triggeredBy, payload);
    return execution;
  }

  async setActive(id: string, tenantId: string, isActive: boolean) {
    const workflow = await this.getById(id, tenantId);
    return prisma.workflow.update({
      where: { id: workflow.id },
      data: { isActive },
    });
  }
}
