import { prisma } from '../database/prisma';
import { NotFoundError, ForbiddenError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';

export class TemplateService {
  async list(tenantId: string, options: { page: number; limit: number }) {
    const { page, limit } = options;
    const skip = (page - 1) * limit;
    const where = { tenantId };
    const [templates, total] = await Promise.all([
      prisma.notificationTemplate.findMany({ where, skip, take: limit, orderBy: { createdAt: 'desc' } }),
      prisma.notificationTemplate.count({ where }),
    ]);
    return { data: templates, meta: createPaginationMeta(total, page, limit) };
  }

  async create(dto: {
    name: string;
    channel: string;
    subject?: string;
    body: string;
    variables: string[];
    tenantId: string;
  }) {
    return prisma.notificationTemplate.create({
      data: {
        name: dto.name,
        channel: dto.channel,
        subject: dto.subject,
        body: dto.body,
        variables: dto.variables,
        tenantId: dto.tenantId,
      },
    });
  }

  async getById(id: string, tenantId: string) {
    const template = await prisma.notificationTemplate.findUnique({ where: { id } });
    if (!template) throw new NotFoundError('Template', id);
    if (template.tenantId !== tenantId) throw new ForbiddenError();
    return template;
  }

  async update(
    id: string,
    tenantId: string,
    dto: { name?: string; subject?: string; body?: string; variables?: string[] }
  ) {
    const template = await this.getById(id, tenantId);
    return prisma.notificationTemplate.update({
      where: { id: template.id },
      data: {
        ...(dto.name && { name: dto.name }),
        ...(dto.subject !== undefined && { subject: dto.subject }),
        ...(dto.body && { body: dto.body }),
        ...(dto.variables && { variables: dto.variables }),
      },
    });
  }

  async delete(id: string, tenantId: string): Promise<void> {
    const template = await this.getById(id, tenantId);
    await prisma.notificationTemplate.delete({ where: { id: template.id } });
  }
}
