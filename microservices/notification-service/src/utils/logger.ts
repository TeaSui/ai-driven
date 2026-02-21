export class Logger {
  constructor(private readonly serviceName: string) {}
  private log(level: string, message: string, data?: Record<string, unknown>): void {
    const entry = { level, message, service: this.serviceName, timestamp: new Date().toISOString(), ...data };
    level === 'error' || level === 'warn' ? console.error(JSON.stringify(entry)) : console.log(JSON.stringify(entry));
  }
  info(message: string, data?: Record<string, unknown>): void { this.log('info', message, data); }
  warn(message: string, data?: Record<string, unknown>): void { this.log('warn', message, data); }
  error(message: string, error?: Error, data?: Record<string, unknown>): void {
    this.log('error', message, { ...data, ...(error && { errorMessage: error.message }) });
  }
  debug(message: string, data?: Record<string, unknown>): void {
    if (process.env.NODE_ENV === 'development') this.log('debug', message, data);
  }
}
