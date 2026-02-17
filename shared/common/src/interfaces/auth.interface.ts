export interface IUser {
  id: string;
  tenantId: string;
  email: string;
  role: UserRole;
  permissions: string[];
  isActive: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export interface ITokenPayload {
  userId: string;
  tenantId: string;
  email: string;
  role: UserRole;
  permissions: string[];
}

export interface IAuthResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: Omit<IUser, 'createdAt' | 'updatedAt'>;
}

export enum UserRole {
  SUPER_ADMIN = 'super_admin',
  TENANT_ADMIN = 'tenant_admin',
  MANAGER = 'manager',
  USER = 'user',
  VIEWER = 'viewer',
}
