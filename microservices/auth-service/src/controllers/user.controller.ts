import { Request, Response } from 'express';
import { UserService } from '../services/user.service';

const userService = new UserService();

export class UserController {
  list = async (req: Request, res: Response): Promise<void> => {
    const tenantId = req.headers['x-tenant-id'] as string;
    const { page = 1, limit = 20, search } = req.query;
    const result = await userService.list(tenantId, {
      page: Number(page),
      limit: Number(limit),
      search: search as string | undefined,
    });
    res.json({ success: true, ...result });
  };

  getById = async (req: Request, res: Response): Promise<void> => {
    const user = await userService.getById(req.params.id);
    res.json({ success: true, data: user });
  };

  update = async (req: Request, res: Response): Promise<void> => {
    const user = await userService.update(req.params.id, req.body);
    res.json({ success: true, data: user });
  };

  deactivate = async (req: Request, res: Response): Promise<void> => {
    await userService.deactivate(req.params.id);
    res.json({ success: true, message: 'User deactivated' });
  };
}
