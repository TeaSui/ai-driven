import { IWorkflowStep, IWorkflowTrigger } from '../interfaces/workflow.interface';

export interface CreateWorkflowDto {
  name: string;
  description: string;
  steps: IWorkflowStep[];
  triggers?: IWorkflowTrigger[];
}

export interface UpdateWorkflowDto {
  name?: string;
  description?: string;
  steps?: IWorkflowStep[];
  triggers?: IWorkflowTrigger[];
}

export interface ExecuteWorkflowDto {
  workflowId: string;
  input?: Record<string, unknown>;
}

export interface WorkflowQueryDto {
  page?: number;
  limit?: number;
  status?: string;
  search?: string;
}
