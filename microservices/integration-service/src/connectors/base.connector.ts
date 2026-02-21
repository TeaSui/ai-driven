export interface ConnectorTestResult {
  success: boolean;
  message: string;
}

export abstract class BaseConnector {
  constructor(
    protected readonly config: Record<string, unknown>,
    protected readonly credentials: Record<string, string>
  ) {}

  abstract test(): Promise<ConnectorTestResult>;
  abstract execute(action: string, params: Record<string, unknown>): Promise<Record<string, unknown>>;

  protected getCredential(key: string): string {
    const value = this.credentials[key];
    if (!value) throw new Error(`Missing credential: ${key}`);
    return value;
  }

  protected getConfig<T>(key: string, defaultValue?: T): T {
    return (this.config[key] as T) ?? (defaultValue as T);
  }
}
