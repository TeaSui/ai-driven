import { Router } from 'express';
import { WorkflowController } from '../controllers/workflow.controller';

const router = Router();
const controller = new WorkflowController();

router.get('/', controller.list);
router.post('/', controller.create);
router.get('/:id', controller.getById);
router.put('/:id', controller.update);
router.delete('/:id', controller.delete);
router.post('/:id/trigger', controller.trigger);
router.patch('/:id/activate', controller.activate);
router.patch('/:id/deactivate', controller.deactivate);

export { router as workflowRouter };
