import { HTTP_STATUS } from '../constants/status-codes';

export class AppError extends Error {
  public readonly statusCode: number;
  public readonly code: string;
  public readonly isOperational: boolean;

  constructor(
    message: string,
    statusCode: number = HTTP_STATUS.INTERNAL_SERVER_ERROR,
    code: string = 'INTERNAL_ERROR',
    isOperational: boolean = true
  ) {
    super(message);
    this.name = this.constructor.name;
    this.statusCode = statusCode;
    this.code = code;
    this.isOperational = isOperational;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class NotFoundError extends AppError {
  constructor(resource: string, id?: string) {
    super(
      id ? `${resource} with id '${id}' not found` : `${resource} not found`,
      HTTP_STATUS.NOT_FOUND,
      'NOT_FOUND'
    );
  }
}

export class ValidationError extends AppError {
  public readonly errors: Record<string, string[]>;

  constructor(message: string, errors: Record<string, string[]> = {}) {
    super(message, HTTP_STATUS.BAD_REQUEST, 'VALIDATION_ERROR');
    this.errors = errors;
  }
}

export class UnauthorizedError extends AppError {
  constructor(message: string = 'Unauthorized') {
    super(message, HTTP_STATUS.UNAUTHORIZED, 'UNAUTHORIZED');
  }
}

export class ForbiddenError extends AppError {
  constructor(message: string = 'Forbidden') {
    super(message, HTTP_STATUS.FORBIDDEN, 'FORBIDDEN');
  }
}

export class ConflictError extends AppError {
  constructor(message: string) {
    super(message, HTTP_STATUS.CONFLICT, 'CONFLICT');
  }
}

export class ServiceUnavailableError extends AppError {
  constructor(service: string) {
    super(
      `Service '${service}' is currently unavailable`,
      HTTP_STATUS.SERVICE_UNAVAILABLE,
      'SERVICE_UNAVAILABLE'
    );
  }
}

export interface ErrorResponse {
  success: false;
  error: {
    code: string;
    message: string;
    errors?: Record<string, string[]>;
    timestamp: string;
    requestId?: string;
  };
}

export function formatErrorResponse(
  error: AppError,
  requestId?: string
): ErrorResponse {
  return {
    success: false,
    error: {
      code: error.code,
      message: error.message,
      ...(error instanceof ValidationError && { errors: error.errors }),
      timestamp: new Date().toISOString(),
      ...(requestId && { requestId }),
    },
  };
}
