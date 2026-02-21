import { Router } from 'express';
import { ExecutionController } from '../controllers/execution.controller';

const router = Router();
const controller = new ExecutionController();

router.get('/', controller.list);
router.get('/:id', controller.getById);
router.post('/:id/cancel', controller.cancel);

export { router as executionRouter };
