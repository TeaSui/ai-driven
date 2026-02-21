export enum LogLevel {
  DEBUG = 'debug',
  INFO = 'info',
  WARN = 'warn',
  ERROR = 'error',
}

export interface LogEntry {
  level: LogLevel;
  message: string;
  service: string;
  timestamp: string;
  correlationId?: string;
  tenantId?: string;
  userId?: string;
  data?: Record<string, unknown>;
  error?: {
    message: string;
    stack?: string;
    code?: string;
  };
}

export class Logger {
  private readonly serviceName: string;
  private readonly minLevel: LogLevel;

  private static readonly levelPriority: Record<LogLevel, number> = {
    [LogLevel.DEBUG]: 0,
    [LogLevel.INFO]: 1,
    [LogLevel.WARN]: 2,
    [LogLevel.ERROR]: 3,
  };

  constructor(serviceName: string, minLevel: LogLevel = LogLevel.INFO) {
    this.serviceName = serviceName;
    this.minLevel =
      process.env.NODE_ENV === 'development' ? LogLevel.DEBUG : minLevel;
  }

  private shouldLog(level: LogLevel): boolean {
    return (
      Logger.levelPriority[level] >= Logger.levelPriority[this.minLevel]
    );
  }

  private log(
    level: LogLevel,
    message: string,
    data?: Record<string, unknown>,
    error?: Error
  ): void {
    if (!this.shouldLog(level)) return;

    const entry: LogEntry = {
      level,
      message,
      service: this.serviceName,
      timestamp: new Date().toISOString(),
      ...(data && { data }),
      ...(error && {
        error: {
          message: error.message,
          stack: process.env.NODE_ENV !== 'production' ? error.stack : undefined,
          code: (error as NodeJS.ErrnoException).code,
        },
      }),
    };

    const output = JSON.stringify(entry);

    if (level === LogLevel.ERROR || level === LogLevel.WARN) {
      console.error(output);
    } else {
      console.log(output);
    }
  }

  debug(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.DEBUG, message, data);
  }

  info(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.INFO, message, data);
  }

  warn(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.WARN, message, data);
  }

  error(message: string, error?: Error, data?: Record<string, unknown>): void {
    this.log(LogLevel.ERROR, message, data, error);
  }

  child(context: Record<string, unknown>): ChildLogger {
    return new ChildLogger(this, context);
  }
}

export class ChildLogger {
  constructor(
    private readonly parent: Logger,
    private readonly context: Record<string, unknown>
  ) {}

  debug(message: string, data?: Record<string, unknown>): void {
    this.parent.debug(message, { ...this.context, ...data });
  }

  info(message: string, data?: Record<string, unknown>): void {
    this.parent.info(message, { ...this.context, ...data });
  }

  warn(message: string, data?: Record<string, unknown>): void {
    this.parent.warn(message, { ...this.context, ...data });
  }

  error(message: string, error?: Error, data?: Record<string, unknown>): void {
    this.parent.error(message, error, { ...this.context, ...data });
  }
}
