import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { IAuthResult, ITokenPayload, UserRole, RegisterDto, createLogger } from '@ai-driven/common';

const logger = createLogger('auth-service:service');

const JWT_SECRET = process.env.JWT_SECRET || 'default-dev-secret';
const JWT_EXPIRATION = parseInt(process.env.JWT_EXPIRATION || '3600', 10);

// In-memory store for demo purposes — replace with database in production
const users: Map<string, { id: string; email: string; passwordHash: string; tenantId: string; role: UserRole }> = new Map();

export class AuthService {
  async login(email: string, password: string, tenantSlug: string): Promise<IAuthResult> {
    const user = users.get(`${tenantSlug}:${email}`);
    if (!user) {
      throw new Error('Invalid credentials');
    }

    const isValid = await bcrypt.compare(password, user.passwordHash);
    if (!isValid) {
      throw new Error('Invalid credentials');
    }

    logger.info('User logged in', { userId: user.id, tenantId: user.tenantId });
    return this.generateTokens(user);
  }

  async register(dto: RegisterDto): Promise<IAuthResult> {
    const key = `${dto.tenantSlug}:${dto.email}`;
    if (users.has(key)) {
      throw new Error('User already exists');
    }

    const passwordHash = await bcrypt.hash(dto.password, 12);
    const user = {
      id: `user_${Date.now()}`,
      email: dto.email,
      passwordHash,
      tenantId: dto.tenantSlug,
      role: UserRole.USER,
    };

    users.set(key, user);
    logger.info('User registered', { userId: user.id, tenantId: user.tenantId });
    return this.generateTokens(user);
  }

  async refreshToken(refreshToken: string): Promise<IAuthResult> {
    const payload = jwt.verify(refreshToken, JWT_SECRET) as ITokenPayload;
    const user = Array.from(users.values()).find((u) => u.id === payload.userId);
    if (!user) {
      throw new Error('User not found');
    }
    return this.generateTokens(user);
  }

  async verifyToken(token: string): Promise<ITokenPayload> {
    return jwt.verify(token, JWT_SECRET) as ITokenPayload;
  }

  private generateTokens(user: { id: string; email: string; tenantId: string; role: UserRole }): IAuthResult {
    const payload: ITokenPayload = {
      userId: user.id,
      tenantId: user.tenantId,
      email: user.email,
      role: user.role,
      permissions: [],
    };

    const accessToken = jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_EXPIRATION });
    const refreshToken = jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_EXPIRATION * 24 });

    return {
      accessToken,
      refreshToken,
      expiresIn: JWT_EXPIRATION,
      user: {
        id: user.id,
        tenantId: user.tenantId,
        email: user.email,
        role: user.role,
        permissions: [],
        isActive: true,
      },
    };
  }
}
