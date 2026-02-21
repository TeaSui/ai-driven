import { WorkflowService } from '../../services/workflow.service';
import { prisma } from '../../database/prisma';
import * as rabbitmq from '../../messaging/rabbitmq';

jest.mock('../../database/prisma', () => ({
  prisma: {
    workflow: {
      findMany: jest.fn(),
      findUnique: jest.fn(),
      create: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
      count: jest.fn(),
    },
    workflowExecution: {
      create: jest.fn(),
    },
  },
}));

jest.mock('../../messaging/rabbitmq', () => ({
  publishEvent: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../../engine/execution.engine', () => ({
  ExecutionEngine: jest.fn().mockImplementation(() => ({
    startExecution: jest.fn().mockResolvedValue({ id: 'exec-123', status: 'RUNNING' }),
  })),
}));

const mockWorkflow = {
  id: 'wf-123',
  name: 'Test Workflow',
  description: 'A test workflow',
  triggerType: 'MANUAL',
  triggerConfig: {},
  steps: [],
  isActive: true,
  tags: [],
  tenantId: 'tenant-123',
  createdBy: 'user-123',
  executionCount: 0,
  lastExecutedAt: null,
  createdAt: new Date(),
  updatedAt: new Date(),
};

describe('WorkflowService', () => {
  let service: WorkflowService;

  beforeEach(() => {
    service = new WorkflowService();
    jest.clearAllMocks();
  });

  describe('list', () => {
    it('should return paginated workflows', async () => {
      (prisma.workflow.findMany as jest.Mock).mockResolvedValue([mockWorkflow]);
      (prisma.workflow.count as jest.Mock).mockResolvedValue(1);

      const result = await service.list('tenant-123', { page: 1, limit: 10 });

      expect(result.data).toHaveLength(1);
      expect(result.meta.total).toBe(1);
      expect(result.meta.page).toBe(1);
    });
  });

  describe('create', () => {
    it('should create a workflow and publish event', async () => {
      (prisma.workflow.create as jest.Mock).mockResolvedValue(mockWorkflow);

      const result = await service.create('tenant-123', 'user-123', {
        name: 'Test Workflow',
        trigger: { type: 'MANUAL', config: {} },
        steps: [],
      });

      expect(result.id).toBe('wf-123');
      expect(rabbitmq.publishEvent).toHaveBeenCalledWith(
        'workflow.created',
        expect.objectContaining({ workflowId: 'wf-123' })
      );
    });
  });

  describe('getById', () => {
    it('should return workflow for correct tenant', async () => {
      (prisma.workflow.findUnique as jest.Mock).mockResolvedValue(mockWorkflow);

      const result = await service.getById('wf-123', 'tenant-123');
      expect(result.id).toBe('wf-123');
    });

    it('should throw NotFoundError when workflow does not exist', async () => {
      (prisma.workflow.findUnique as jest.Mock).mockResolvedValue(null);

      await expect(service.getById('nonexistent', 'tenant-123')).rejects.toThrow('not found');
    });

    it('should throw ForbiddenError for wrong tenant', async () => {
      (prisma.workflow.findUnique as jest.Mock).mockResolvedValue(mockWorkflow);

      await expect(service.getById('wf-123', 'other-tenant')).rejects.toThrow('Forbidden');
    });
  });

  describe('trigger', () => {
    it('should trigger an active workflow', async () => {
      (prisma.workflow.findUnique as jest.Mock).mockResolvedValue(mockWorkflow);

      const result = await service.trigger('wf-123', 'tenant-123', 'user-123');
      expect(result).toHaveProperty('id');
    });

    it('should throw error for inactive workflow', async () => {
      (prisma.workflow.findUnique as jest.Mock).mockResolvedValue({
        ...mockWorkflow,
        isActive: false,
      });

      await expect(
        service.trigger('wf-123', 'tenant-123', 'user-123')
      ).rejects.toThrow('not active');
    });
  });
});
