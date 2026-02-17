export interface IServiceResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: IServiceError;
  metadata?: IResponseMetadata;
}

export interface IServiceError {
  code: string;
  message: string;
  details?: unknown;
}

export interface IResponseMetadata {
  page?: number;
  limit?: number;
  total?: number;
  timestamp: string;
  requestId?: string;
}

export function createSuccessResponse<T>(data: T, metadata?: Partial<IResponseMetadata>): IServiceResponse<T> {
  return {
    success: true,
    data,
    metadata: {
      timestamp: new Date().toISOString(),
      ...metadata,
    },
  };
}

export function createErrorResponse(code: string, message: string, details?: unknown): IServiceResponse {
  return {
    success: false,
    error: { code, message, details },
    metadata: {
      timestamp: new Date().toISOString(),
    },
  };
}
