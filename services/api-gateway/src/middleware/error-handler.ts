import { Request, Response, NextFunction } from 'express';
import { AppError, createErrorResponse, createLogger } from '@ai-driven/common';

const logger = createLogger('api-gateway:error-handler');

export function errorHandler(err: Error, _req: Request, res: Response, _next: NextFunction): void {
  logger.error('Unhandled error', { error: err.message, stack: err.stack });

  if (err instanceof AppError) {
    res.status(err.statusCode).json(createErrorResponse(err.code, err.message, err.details));
    return;
  }

  res.status(500).json(createErrorResponse('INTERNAL_ERROR', 'An unexpected error occurred'));
}
