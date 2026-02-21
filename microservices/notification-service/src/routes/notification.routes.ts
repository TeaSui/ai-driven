import { Router } from 'express';
import { NotificationController } from '../controllers/notification.controller';

const router = Router();
const controller = new NotificationController();

router.get('/', controller.list);
router.post('/send', controller.send);
router.get('/:id', controller.getById);

export { router as notificationRouter };
