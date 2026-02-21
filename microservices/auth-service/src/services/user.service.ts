import { prisma } from '../database/prisma';
import { Logger } from '../utils/logger';
import { NotFoundError } from '../utils/errors';
import { createPaginationMeta } from '../utils/pagination';
import type { UserDto, UpdateUserDto } from '../types';

const logger = new Logger('auth-service:user');

export class UserService {
  async list(
    tenantId: string,
    options: { page: number; limit: number; search?: string }
  ): Promise<{ data: UserDto[]; meta: ReturnType<typeof createPaginationMeta> }> {
    const { page, limit, search } = options;
    const skip = (page - 1) * limit;

    const where = {
      tenantId,
      ...(search && {
        OR: [
          { email: { contains: search, mode: 'insensitive' as const } },
          { firstName: { contains: search, mode: 'insensitive' as const } },
          { lastName: { contains: search, mode: 'insensitive' as const } },
        ],
      }),
    };

    const [users, total] = await Promise.all([
      prisma.user.findMany({ where, skip, take: limit, orderBy: { createdAt: 'desc' } }),
      prisma.user.count({ where }),
    ]);

    return {
      data: users.map(this.mapToDto),
      meta: createPaginationMeta(total, page, limit),
    };
  }

  async getById(id: string): Promise<UserDto> {
    const user = await prisma.user.findUnique({ where: { id } });
    if (!user) throw new NotFoundError('User', id);
    return this.mapToDto(user);
  }

  async update(id: string, dto: UpdateUserDto): Promise<UserDto> {
    const user = await prisma.user.findUnique({ where: { id } });
    if (!user) throw new NotFoundError('User', id);

    const updated = await prisma.user.update({
      where: { id },
      data: {
        ...(dto.firstName && { firstName: dto.firstName }),
        ...(dto.lastName && { lastName: dto.lastName }),
        ...(dto.isActive !== undefined && { isActive: dto.isActive }),
        ...(dto.role && { role: dto.role }),
      },
    });

    logger.info('User updated', { userId: id });
    return this.mapToDto(updated);
  }

  async deactivate(id: string): Promise<void> {
    const user = await prisma.user.findUnique({ where: { id } });
    if (!user) throw new NotFoundError('User', id);

    await prisma.user.update({
      where: { id },
      data: { isActive: false },
    });

    logger.info('User deactivated', { userId: id });
  }

  private mapToDto(user: {
    id: string;
    email: string;
    firstName: string;
    lastName: string;
    role: string;
    tenantId: string;
    isActive: boolean;
    createdAt: Date;
    updatedAt: Date;
  }): UserDto {
    return {
      id: user.id,
      email: user.email,
      firstName: user.firstName,
      lastName: user.lastName,
      role: user.role as UserDto['role'],
      tenantId: user.tenantId,
      isActive: user.isActive,
      createdAt: user.createdAt.toISOString(),
      updatedAt: user.updatedAt.toISOString(),
    };
  }
}
