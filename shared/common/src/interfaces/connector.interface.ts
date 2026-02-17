export interface IConnector {
  /** Unique identifier for this connector type */
  readonly type: string;

  /** Human-readable name */
  readonly name: string;

  /** Initialize the connector with tenant-specific config */
  initialize(config: Record<string, unknown>): Promise<void>;

  /** Execute an action through this connector */
  execute(action: string, params: Record<string, unknown>): Promise<IConnectorResult>;

  /** Validate that the connector configuration is correct */
  validateConfig(config: Record<string, unknown>): Promise<boolean>;

  /** List available actions for this connector */
  getAvailableActions(): IConnectorAction[];

  /** Clean up resources */
  disconnect(): Promise<void>;
}

export interface IConnectorResult {
  success: boolean;
  data?: unknown;
  error?: string;
  metadata?: Record<string, unknown>;
}

export interface IConnectorAction {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
}

export interface IConnectorRegistry {
  register(connector: IConnector): void;
  get(type: string): IConnector | undefined;
  getAll(): IConnector[];
  has(type: string): boolean;
}
