import { Router } from 'express';
import { AuthController } from '../controllers/auth.controller';
import { validateBody } from '../middleware/validate.middleware';
import { loginSchema, registerSchema, refreshTokenSchema } from '../validators/auth.validator';

const router = Router();
const controller = new AuthController();

router.post('/register', validateBody(registerSchema), controller.register);
router.post('/login', validateBody(loginSchema), controller.login);
router.post('/refresh', validateBody(refreshTokenSchema), controller.refresh);
router.post('/logout', controller.logout);
router.get('/me', controller.me);

export { router as authRouter };
