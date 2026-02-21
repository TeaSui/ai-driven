import {
  AppError,
  NotFoundError,
  ValidationError,
  UnauthorizedError,
  ForbiddenError,
  ConflictError,
  ServiceUnavailableError,
  formatErrorResponse,
} from '../../utils/errors';
import { HTTP_STATUS } from '../../constants/status-codes';

describe('AppError', () => {
  it('should create an error with default values', () => {
    const error = new AppError('Something went wrong');
    expect(error.message).toBe('Something went wrong');
    expect(error.statusCode).toBe(HTTP_STATUS.INTERNAL_SERVER_ERROR);
    expect(error.code).toBe('INTERNAL_ERROR');
    expect(error.isOperational).toBe(true);
  });

  it('should create an error with custom values', () => {
    const error = new AppError('Custom error', 400, 'CUSTOM_CODE', false);
    expect(error.statusCode).toBe(400);
    expect(error.code).toBe('CUSTOM_CODE');
    expect(error.isOperational).toBe(false);
  });
});

describe('NotFoundError', () => {
  it('should create a not found error with resource name', () => {
    const error = new NotFoundError('User');
    expect(error.statusCode).toBe(HTTP_STATUS.NOT_FOUND);
    expect(error.code).toBe('NOT_FOUND');
    expect(error.message).toContain('User');
  });

  it('should include id in message when provided', () => {
    const error = new NotFoundError('Workflow', '123');
    expect(error.message).toContain('123');
  });
});

describe('ValidationError', () => {
  it('should create a validation error with field errors', () => {
    const errors = { email: ['Invalid email'], password: ['Too short'] };
    const error = new ValidationError('Validation failed', errors);
    expect(error.statusCode).toBe(HTTP_STATUS.BAD_REQUEST);
    expect(error.errors).toEqual(errors);
  });
});

describe('UnauthorizedError', () => {
  it('should create an unauthorized error', () => {
    const error = new UnauthorizedError();
    expect(error.statusCode).toBe(HTTP_STATUS.UNAUTHORIZED);
    expect(error.code).toBe('UNAUTHORIZED');
  });
});

describe('ForbiddenError', () => {
  it('should create a forbidden error', () => {
    const error = new ForbiddenError();
    expect(error.statusCode).toBe(HTTP_STATUS.FORBIDDEN);
    expect(error.code).toBe('FORBIDDEN');
  });
});

describe('ConflictError', () => {
  it('should create a conflict error', () => {
    const error = new ConflictError('Resource already exists');
    expect(error.statusCode).toBe(HTTP_STATUS.CONFLICT);
    expect(error.code).toBe('CONFLICT');
  });
});

describe('ServiceUnavailableError', () => {
  it('should create a service unavailable error', () => {
    const error = new ServiceUnavailableError('auth-service');
    expect(error.statusCode).toBe(HTTP_STATUS.SERVICE_UNAVAILABLE);
    expect(error.message).toContain('auth-service');
  });
});

describe('formatErrorResponse', () => {
  it('should format error response correctly', () => {
    const error = new NotFoundError('User', '123');
    const response = formatErrorResponse(error, 'req-123');
    expect(response.success).toBe(false);
    expect(response.error.code).toBe('NOT_FOUND');
    expect(response.error.requestId).toBe('req-123');
    expect(response.error.timestamp).toBeDefined();
  });

  it('should include validation errors for ValidationError', () => {
    const fieldErrors = { email: ['Invalid'] };
    const error = new ValidationError('Failed', fieldErrors);
    const response = formatErrorResponse(error);
    expect(response.error.errors).toEqual(fieldErrors);
  });
});
