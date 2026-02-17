import { Router, Request, Response } from 'express';
import { AuthService } from '../services/auth.service';
import { createSuccessResponse, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('auth-service:routes');
const authService = new AuthService();
export const authRouter = Router();

authRouter.post('/login', async (req: Request, res: Response) => {
  try {
    const { email, password, tenantSlug } = req.body;

    if (!email || !password || !tenantSlug) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'Email, password, and tenantSlug are required'));
      return;
    }

    const result = await authService.login(email, password, tenantSlug);
    res.json(createSuccessResponse(result));
  } catch (error: any) {
    logger.error('Login failed', { error: error.message });
    res.status(401).json(createErrorResponse('AUTH_FAILED', error.message));
  }
});

authRouter.post('/register', async (req: Request, res: Response) => {
  try {
    const { email, password, tenantSlug, firstName, lastName } = req.body;

    if (!email || !password || !tenantSlug) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'Email, password, and tenantSlug are required'));
      return;
    }

    const result = await authService.register({ email, password, tenantSlug, firstName, lastName });
    res.status(201).json(createSuccessResponse(result));
  } catch (error: any) {
    logger.error('Registration failed', { error: error.message });
    res.status(400).json(createErrorResponse('REGISTRATION_FAILED', error.message));
  }
});

authRouter.post('/refresh', async (req: Request, res: Response) => {
  try {
    const { refreshToken } = req.body;

    if (!refreshToken) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'Refresh token is required'));
      return;
    }

    const result = await authService.refreshToken(refreshToken);
    res.json(createSuccessResponse(result));
  } catch (error: any) {
    logger.error('Token refresh failed', { error: error.message });
    res.status(401).json(createErrorResponse('REFRESH_FAILED', error.message));
  }
});

authRouter.post('/verify', async (req: Request, res: Response) => {
  try {
    const { token } = req.body;

    if (!token) {
      res.status(400).json(createErrorResponse('VALIDATION_ERROR', 'Token is required'));
      return;
    }

    const payload = await authService.verifyToken(token);
    res.json(createSuccessResponse(payload));
  } catch (error: any) {
    res.status(401).json(createErrorResponse('INVALID_TOKEN', error.message));
  }
});
