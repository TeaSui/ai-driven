import { Router } from 'express';
import { TemplateController } from '../controllers/template.controller';

const router = Router();
const controller = new TemplateController();

router.get('/', controller.list);
router.post('/', controller.create);
router.get('/:id', controller.getById);
router.put('/:id', controller.update);
router.delete('/:id', controller.delete);

export { router as templateRouter };
