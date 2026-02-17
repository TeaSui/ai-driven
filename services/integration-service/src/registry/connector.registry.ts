import { IConnector, IConnectorRegistry, createLogger } from '@ai-driven/common';

const logger = createLogger('integration-service:registry');

export class ConnectorRegistry implements IConnectorRegistry {
  private static instance: ConnectorRegistry;
  private connectors: Map<string, IConnector> = new Map();

  private constructor() {}

  static getInstance(): ConnectorRegistry {
    if (!ConnectorRegistry.instance) {
      ConnectorRegistry.instance = new ConnectorRegistry();
    }
    return ConnectorRegistry.instance;
  }

  register(connector: IConnector): void {
    this.connectors.set(connector.type, connector);
    logger.info('Connector registered', { type: connector.type, name: connector.name });
  }

  get(type: string): IConnector | undefined {
    return this.connectors.get(type);
  }

  getAll(): IConnector[] {
    return Array.from(this.connectors.values());
  }

  has(type: string): boolean {
    return this.connectors.has(type);
  }
}
