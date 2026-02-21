import cron from 'node-cron';
import { prisma } from '../database/prisma';
import { ExecutionEngine } from '../engine/execution.engine';
import { Logger } from '../utils/logger';

const logger = new Logger('workflow-service:scheduler');
const engine = new ExecutionEngine();

export class WorkflowScheduler {
  private tasks: Map<string, cron.ScheduledTask> = new Map();

  async start(): Promise<void> {
    await this.loadScheduledWorkflows();
    logger.info('Workflow scheduler started');
  }

  private async loadScheduledWorkflows(): Promise<void> {
    const workflows = await prisma.workflow.findMany({
      where: { triggerType: 'SCHEDULED', isActive: true },
    });

    for (const workflow of workflows) {
      this.scheduleWorkflow(workflow);
    }

    logger.info(`Loaded ${workflows.length} scheduled workflows`);
  }

  scheduleWorkflow(workflow: {
    id: string;
    name: string;
    tenantId: string;
    steps: unknown;
    triggerType: string;
    triggerConfig: unknown;
  }): void {
    const config = workflow.triggerConfig as { schedule?: string };
    const schedule = config?.schedule;

    if (!schedule || !cron.validate(schedule)) {
      logger.warn('Invalid cron schedule for workflow', {
        workflowId: workflow.id,
        schedule,
      });
      return;
    }

    // Remove existing task if any
    this.unscheduleWorkflow(workflow.id);

    const task = cron.schedule(schedule, async () => {
      logger.info('Triggering scheduled workflow', { workflowId: workflow.id });
      try {
        await engine.startExecution(workflow, 'scheduler');
      } catch (error) {
        logger.error('Failed to trigger scheduled workflow', error as Error, {
          workflowId: workflow.id,
        });
      }
    });

    this.tasks.set(workflow.id, task);
    logger.info('Workflow scheduled', { workflowId: workflow.id, schedule });
  }

  unscheduleWorkflow(workflowId: string): void {
    const task = this.tasks.get(workflowId);
    if (task) {
      task.stop();
      this.tasks.delete(workflowId);
    }
  }

  stop(): void {
    for (const [id, task] of this.tasks) {
      task.stop();
      this.tasks.delete(id);
    }
    logger.info('Workflow scheduler stopped');
  }
}
