import { AuthService } from '../../services/auth.service';
import { prisma } from '../../database/prisma';
import * as rabbitmq from '../../messaging/rabbitmq';
import bcrypt from 'bcryptjs';

jest.mock('../../database/prisma', () => ({
  prisma: {
    user: {
      findUnique: jest.fn(),
      create: jest.fn(),
      update: jest.fn(),
    },
    refreshToken: {
      create: jest.fn(),
      findUnique: jest.fn(),
      update: jest.fn(),
      updateMany: jest.fn(),
    },
    auditLog: {
      create: jest.fn(),
    },
  },
}));

jest.mock('../../messaging/rabbitmq', () => ({
  publishEvent: jest.fn().mockResolvedValue(undefined),
}));

const mockUser = {
  id: 'user-123',
  email: 'test@example.com',
  passwordHash: '',
  firstName: 'John',
  lastName: 'Doe',
  role: 'USER',
  tenantId: 'tenant-123',
  isActive: true,
  createdAt: new Date(),
  updatedAt: new Date(),
};

describe('AuthService', () => {
  let authService: AuthService;

  beforeEach(() => {
    authService = new AuthService();
    jest.clearAllMocks();
  });

  describe('register', () => {
    it('should register a new user successfully', async () => {
      (prisma.user.findUnique as jest.Mock).mockResolvedValue(null);
      (prisma.user.create as jest.Mock).mockResolvedValue(mockUser);
      (prisma.refreshToken.create as jest.Mock).mockResolvedValue({});

      const result = await authService.register({
        email: 'test@example.com',
        password: 'password123',
        firstName: 'John',
        lastName: 'Doe',
      });

      expect(result).toHave.property('accessToken');
      expect(result).toHaveProperty('refreshToken');
      expect(result.user.email).toBe('test@example.com');
      expect(rabbitmq.publishEvent).toHaveBeenCalledWith(
        'auth.user.registered',
        expect.objectContaining({ email: 'test@example.com' })
      );
    });

    it('should throw ConflictError if user already exists', async () => {
      (prisma.user.findUnique as jest.Mock).mockResolvedValue(mockUser);

      await expect(
        authService.register({
          email: 'test@example.com',
          password: 'password123',
          firstName: 'John',
          lastName: 'Doe',
        })
      ).rejects.toThrow('already exists');
    });
  });

  describe('login', () => {
    it('should login successfully with valid credentials', async () => {
      const hash = await bcrypt.hash('password123', 10);
      const userWithHash = { ...mockUser, passwordHash: hash };

      (prisma.user.findUnique as jest.Mock).mockResolvedValue(userWithHash);
      (prisma.auditLog.create as jest.Mock).mockResolvedValue({});
      (prisma.refreshToken.create as jest.Mock).mockResolvedValue({});

      const result = await authService.login({
        email: 'test@example.com',
        password: 'password123',
      });

      expect(result).toHaveProperty('accessToken');
      expect(rabbitmq.publishEvent).toHaveBeenCalledWith(
        'auth.user.logged_in',
        expect.objectContaining({ email: 'test@example.com' })
      );
    });

    it('should throw UnauthorizedError for invalid password', async () => {
      const hash = await bcrypt.hash('correctpassword', 10);
      (prisma.user.findUnique as jest.Mock).mockResolvedValue({
        ...mockUser,
        passwordHash: hash,
      });

      await expect(
        authService.login({ email: 'test@example.com', password: 'wrongpassword' })
      ).rejects.toThrow('Invalid credentials');
    });

    it('should throw UnauthorizedError for inactive user', async () => {
      (prisma.user.findUnique as jest.Mock).mockResolvedValue({
        ...mockUser,
        isActive: false,
      });

      await expect(
        authService.login({ email: 'test@example.com', password: 'password123' })
      ).rejects.toThrow('Invalid credentials');
    });

    it('should throw UnauthorizedError for non-existent user', async () => {
      (prisma.user.findUnique as jest.Mock).mockResolvedValue(null);

      await expect(
        authService.login({ email: 'nobody@example.com', password: 'password123' })
      ).rejects.toThrow('Invalid credentials');
    });
  });

  describe('refreshToken', () => {
    it('should refresh token successfully', async () => {
      const futureDate = new Date();
      futureDate.setDate(futureDate.getDate() + 30);

      (prisma.refreshToken.findUnique as jest.Mock).mockResolvedValue({
        id: 'token-123',
        token: 'valid-refresh-token',
        revokedAt: null,
        expiresAt: futureDate,
        user: mockUser,
      });
      (prisma.refreshToken.update as jest.Mock).mockResolvedValue({});
      (prisma.refreshToken.create as jest.Mock).mockResolvedValue({});

      const result = await authService.refreshToken('valid-refresh-token');
      expect(result).toHaveProperty('accessToken');
    });

    it('should throw UnauthorizedError for revoked token', async () => {
      (prisma.refreshToken.findUnique as jest.Mock).mockResolvedValue({
        id: 'token-123',
        revokedAt: new Date(),
        expiresAt: new Date(),
        user: mockUser,
      });

      await expect(authService.refreshToken('revoked-token')).rejects.toThrow(
        'Invalid or expired refresh token'
      );
    });
  });
});
