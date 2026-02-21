import { Router } from 'express';
import { UserController } from '../controllers/user.controller';
import { requireRole } from '../middleware/role.middleware';

const router = Router();
const controller = new UserController();

router.get('/', requireRole(['SUPER_ADMIN', 'TENANT_ADMIN']), controller.list);
router.get('/:id', controller.getById);
router.patch('/:id', controller.update);
router.delete('/:id', requireRole(['SUPER_ADMIN', 'TENANT_ADMIN']), controller.deactivate);

export { router as userRouter };
