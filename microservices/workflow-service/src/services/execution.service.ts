import { prisma } from '../database/prisma';
import { Logger } from '../utils/logger';
import { NotFoundError, ForbiddenError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';

const logger = new Logger('workflow-service:execution');

export class ExecutionService {
  async list(
    tenantId: string,
    options: {
      page: number;
      limit: number;
      workflowId?: string;
      status?: string;
    }
  ) {
    const { page, limit, workflowId, status } = options;
    const skip = (page - 1) * limit;

    const where = {
      tenantId,
      ...(workflowId && { workflowId }),
      ...(status && { status }),
    };

    const [executions, total] = await Promise.all([
      prisma.workflowExecution.findMany({
        where,
        skip,
        take: limit,
        orderBy: { startedAt: 'desc' },
        include: { workflow: { select: { name: true } } },
      }),
      prisma.workflowExecution.count({ where }),
    ]);

    return { data: executions, meta: createPaginationMeta(total, page, limit) };
  }

  async getById(id: string, tenantId: string) {
    const execution = await prisma.workflowExecution.findUnique({
      where: { id },
      include: { workflow: { select: { name: true } } },
    });
    if (!execution) throw new NotFoundError('Execution', id);
    if (execution.tenantId !== tenantId) throw new ForbiddenError();
    return execution;
  }

  async cancel(id: string, tenantId: string): Promise<void> {
    const execution = await this.getById(id, tenantId);

    if (!['PENDING', 'RUNNING'].includes(execution.status)) {
      throw new Error(`Cannot cancel execution in status: ${execution.status}`);
    }

    await prisma.workflowExecution.update({
      where: { id: execution.id },
      data: {
        status: 'CANCELLED',
        completedAt: new Date(),
      },
    });

    logger.info('Execution cancelled', { executionId: id });
  }
}
