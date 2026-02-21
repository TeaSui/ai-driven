import { Router } from 'express';
import { IntegrationController } from '../controllers/integration.controller';

const router = Router();
const controller = new IntegrationController();

router.get('/', controller.list);
router.post('/', controller.create);
router.get('/:id', controller.getById);
router.put('/:id', controller.update);
router.delete('/:id', controller.delete);
router.post('/:id/test', controller.test);
router.post('/execute', controller.execute);

export { router as integrationRouter };
