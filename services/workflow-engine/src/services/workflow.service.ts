import {
  IWorkflow,
  IWorkflowExecution,
  WorkflowStatus,
  ExecutionStatus,
  CreateWorkflowDto,
  UpdateWorkflowDto,
  createLogger,
} from '@ai-driven/common';

const logger = createLogger('workflow-engine:service');

// In-memory store — replace with database in production
const workflows: Map<string, IWorkflow> = new Map();
const executions: Map<string, IWorkflowExecution> = new Map();

export class WorkflowService {
  async create(tenantId: string, dto: CreateWorkflowDto): Promise<IWorkflow> {
    const workflow: IWorkflow = {
      id: `wf_${Date.now()}`,
      tenantId,
      name: dto.name,
      description: dto.description,
      steps: dto.steps,
      triggers: dto.triggers || [],
      status: WorkflowStatus.DRAFT,
      version: 1,
      createdAt: new Date(),
      updatedAt: new Date(),
    };

    workflows.set(workflow.id, workflow);
    logger.info('Workflow created', { workflowId: workflow.id, tenantId });
    return workflow;
  }

  async getById(id: string): Promise<IWorkflow | undefined> {
    return workflows.get(id);
  }

  async listByTenant(tenantId: string): Promise<IWorkflow[]> {
    return Array.from(workflows.values()).filter((w) => w.tenantId === tenantId);
  }

  async update(id: string, dto: UpdateWorkflowDto): Promise<IWorkflow> {
    const workflow = workflows.get(id);
    if (!workflow) {
      throw new Error('Workflow not found');
    }

    const updated: IWorkflow = {
      ...workflow,
      ...dto,
      version: workflow.version + 1,
      updatedAt: new Date(),
    };

    workflows.set(id, updated);
    logger.info('Workflow updated', { workflowId: id });
    return updated;
  }

  async delete(id: string): Promise<void> {
    if (!workflows.has(id)) {
      throw new Error('Workflow not found');
    }
    workflows.delete(id);
    logger.info('Workflow deleted', { workflowId: id });
  }

  async execute(workflowId: string, tenantId: string, input?: Record<string, unknown>): Promise<IWorkflowExecution> {
    const workflow = workflows.get(workflowId);
    if (!workflow) {
      throw new Error('Workflow not found');
    }

    if (workflow.tenantId !== tenantId) {
      throw new Error('Workflow does not belong to this tenant');
    }

    const execution: IWorkflowExecution = {
      id: `exec_${Date.now()}`,
      workflowId,
      tenantId,
      status: ExecutionStatus.PENDING,
      currentStepId: workflow.steps.length > 0 ? workflow.steps[0].id : null,
      context: { input: input || {} },
      startedAt: new Date(),
      completedAt: null,
      error: null,
    };

    executions.set(execution.id, execution);
    logger.info('Workflow execution started', { executionId: execution.id, workflowId });

    // Async execution simulation — in production, use a job queue
    this.processExecution(execution, workflow).catch((err) => {
      logger.error('Execution failed', { executionId: execution.id, error: err.message });
    });

    return execution;
  }

  private async processExecution(execution: IWorkflowExecution, workflow: IWorkflow): Promise<void> {
    execution.status = ExecutionStatus.RUNNING;
    executions.set(execution.id, execution);

    try {
      for (const step of workflow.steps) {
        execution.currentStepId = step.id;
        executions.set(execution.id, execution);
        logger.info('Processing step', { executionId: execution.id, stepId: step.id, stepType: step.type });

        // Simulate step processing
        await new Promise((resolve) => setTimeout(resolve, 100));
      }

      execution.status = ExecutionStatus.COMPLETED;
      execution.completedAt = new Date();
      execution.currentStepId = null;
    } catch (error: any) {
      execution.status = ExecutionStatus.FAILED;
      execution.error = error.message;
      execution.completedAt = new Date();
    }

    executions.set(execution.id, execution);
    logger.info('Workflow execution finished', { executionId: execution.id, status: execution.status });
  }
}
