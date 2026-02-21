import { Request, Response } from 'express';
import { AuthService } from '../services/auth.service';

const authService = new AuthService();

export class AuthController {
  register = async (req: Request, res: Response): Promise<void> => {
    const result = await authService.register(req.body);
    res.status(201).json({ success: true, data: result });
  };

  login = async (req: Request, res: Response): Promise<void> => {
    const ipAddress = req.ip || req.socket.remoteAddress;
    const userAgent = req.headers['user-agent'];
    const result = await authService.login(req.body, ipAddress, userAgent);
    res.json({ success: true, data: result });
  };

  refresh = async (req: Request, res: Response): Promise<void> => {
    const result = await authService.refreshToken(req.body.refreshToken);
    res.json({ success: true, data: result });
  };

  logout = async (req: Request, res: Response): Promise<void> => {
    const token = req.headers.authorization?.slice(7);
    if (token) {
      await authService.logout(token);
    }
    res.json({ success: true, message: 'Logged out successfully' });
  };

  me = async (req: Request, res: Response): Promise<void> => {
    const userId = req.headers['x-user-id'] as string;
    const user = await authService.getUserById(userId);
    res.json({ success: true, data: user });
  };
}
