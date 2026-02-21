import { BaseConnector } from './base.connector';
import { SlackConnector } from './slack.connector';
import { WebhookConnector } from './webhook.connector';

export class ConnectorFactory {
  static create(
    type: string,
    config: Record<string, unknown>,
    credentials: Record<string, string>
  ): BaseConnector {
    switch (type.toUpperCase()) {
      case 'SLACK':
        return new SlackConnector(config, credentials);
      case 'WEBHOOK':
        return new WebhookConnector(config, credentials);
      default:
        throw new Error(`Unsupported integration type: ${type}`);
    }
  }

  static getSupportedTypes(): string[] {
    return ['SLACK', 'WEBHOOK'];
  }
}
