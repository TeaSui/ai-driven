export interface LoginDto {
  email: string;
  password: string;
  tenantSlug: string;
}

export interface RegisterDto {
  email: string;
  password: string;
  tenantSlug: string;
  firstName?: string;
  lastName?: string;
}

export interface RefreshTokenDto {
  refreshToken: string;
}

export interface ChangePasswordDto {
  currentPassword: string;
  newPassword: string;
}
