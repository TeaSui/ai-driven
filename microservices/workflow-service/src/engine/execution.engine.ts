import { prisma } from '../database/prisma';
import { publishEvent } from '../messaging/rabbitmq';
import { Logger } from '../utils/logger';
import axios from 'axios';

const logger = new Logger('workflow-service:engine');

const INTEGRATION_SERVICE_URL =
  process.env.INTEGRATION_SERVICE_URL || 'http://integration-service:8083';

interface WorkflowStep {
  id: string;
  name: string;
  type: string;
  integrationId?: string;
  action: string;
  config: Record<string, unknown>;
  conditions?: Array<{ field: string; operator: string; value: unknown }>;
  onSuccess?: string;
  onFailure?: string;
  retryPolicy?: { maxAttempts: number; backoffMs: number; backoffMultiplier?: number };
}

interface WorkflowRecord {
  id: string;
  name: string;
  tenantId: string;
  steps: unknown;
  triggerType: string;
  triggerConfig: unknown;
}

export class ExecutionEngine {
  async startExecution(
    workflow: WorkflowRecord,
    triggeredBy: string,
    payload?: Record<string, unknown>
  ) {
    const execution = await prisma.workflowExecution.create({
      data: {
        workflowId: workflow.id,
        status: 'RUNNING',
        triggeredBy,
        payload: (payload ?? {}) as object,
        tenantId: workflow.tenantId,
      },
    });

    await publishEvent('workflow.execution.started', {
      executionId: execution.id,
      workflowId: workflow.id,
      workflowName: workflow.name,
      tenantId: workflow.tenantId,
      triggeredBy,
      triggerPayload: payload,
    });

    // Run asynchronously
    this.runExecution(execution.id, workflow, payload).catch((err) => {
      logger.error('Execution runner crashed', err);
    });

    return execution;
  }

  private async runExecution(
    executionId: string,
    workflow: WorkflowRecord,
    payload?: Record<string, unknown>
  ): Promise<void> {
    const startTime = Date.now();
    const steps = (workflow.steps as WorkflowStep[]) || [];
    const stepResults: unknown[] = [];
    let hasError = false;
    let errorMessage = '';

    try {
      for (const step of steps) {
        const stepResult = await this.executeStep(
          step,
          executionId,
          workflow.tenantId,
          payload
        );
        stepResults.push(stepResult);

        if (stepResult.status === 'FAILED') {
          hasError = true;
          errorMessage = stepResult.error || 'Step failed';

          await publishEvent('workflow.step.failed', {
            executionId,
            workflowId: workflow.id,
            stepId: step.id,
            stepName: step.name,
            tenantId: workflow.tenantId,
            error: errorMessage,
            attempt: stepResult.attempt,
          });

          if (step.onFailure !== 'continue') break;
        }
      }

      const durationMs = Date.now() - startTime;
      const finalStatus = hasError ? 'FAILED' : 'SUCCESS';

      await prisma.workflowExecution.update({
        where: { id: executionId },
        data: {
          status: finalStatus,
          completedAt: new Date(),
          durationMs,
          stepResults: stepResults as object,
          ...(hasError && { error: errorMessage }),
        },
      });

      await prisma.workflow.update({
        where: { id: workflow.id },
        data: {
          executionCount: { increment: 1 },
          lastExecutedAt: new Date(),
        },
      });

      const eventType = hasError
        ? 'workflow.execution.failed'
        : 'workflow.execution.completed';

      await publishEvent(eventType, {
        executionId,
        workflowId: workflow.id,
        workflowName: workflow.name,
        tenantId: workflow.tenantId,
        status: finalStatus,
        durationMs,
        stepsExecuted: stepResults.length,
        ...(hasError && { error: errorMessage }),
      });
    } catch (error) {
      const durationMs = Date.now() - startTime;
      const errMsg = (error as Error).message;

      await prisma.workflowExecution.update({
        where: { id: executionId },
        data: {
          status: 'FAILED',
          completedAt: new Date(),
          durationMs,
          error: errMsg,
          stepResults: stepResults as object,
        },
      });

      await publishEvent('workflow.execution.failed', {
        executionId,
        workflowId: workflow.id,
        workflowName: workflow.name,
        tenantId: workflow.tenantId,
        error: errMsg,
        durationMs,
      });
    }
  }

  private async executeStep(
    step: WorkflowStep,
    executionId: string,
    tenantId: string,
    context?: Record<string, unknown>
  ): Promise<{
    stepId: string;
    stepName: string;
    status: string;
    startedAt: string;
    completedAt?: string;
    output?: Record<string, unknown>;
    error?: string;
    attempt: number;
  }> {
    const maxAttempts = step.retryPolicy?.maxAttempts ?? 1;
    const backoffMs = step.retryPolicy?.backoffMs ?? 1000;
    const startedAt = new Date().toISOString();
    let lastError = '';

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        let output: Record<string, unknown> = {};

        if (step.type === 'ACTION' && step.integrationId) {
          const response = await axios.post(
            `${INTEGRATION_SERVICE_URL}/api/v1/integrations/execute`,
            {
              integrationId: step.integrationId,
              action: step.action,
              params: { ...step.config, ...context },
              workflowExecutionId: executionId,
              stepId: step.id,
            },
            {
              headers: { 'x-tenant-id': tenantId },
              timeout: 30000,
            }
          );
          output = response.data?.data ?? {};
        } else if (step.type === 'DELAY') {
          const delayMs = (step.config.delayMs as number) || 1000;
          await new Promise((resolve) => setTimeout(resolve, Math.min(delayMs, 30000)));
        }

        return {
          stepId: step.id,
          stepName: step.name,
          status: 'SUCCESS',
          startedAt,
          completedAt: new Date().toISOString(),
          output,
          attempt,
        };
      } catch (error) {
        lastError = (error as Error).message;
        logger.warn(`Step attempt ${attempt}/${maxAttempts} failed`, {
          stepId: step.id,
          error: lastError,
        });

        if (attempt < maxAttempts) {
          const delay = backoffMs * Math.pow(step.retryPolicy?.backoffMultiplier ?? 1, attempt - 1);
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }
    }

    return {
      stepId: step.id,
      stepName: step.name,
      status: 'FAILED',
      startedAt,
      completedAt: new Date().toISOString(),
      error: lastError,
      attempt: maxAttempts,
    };
  }
}
