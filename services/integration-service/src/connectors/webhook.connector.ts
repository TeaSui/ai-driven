import { IConnector, IConnectorResult, IConnectorAction, createLogger } from '@ai-driven/common';

const logger = createLogger('integration-service:webhook');

export class WebhookConnector implements IConnector {
  readonly type = 'webhook';
  readonly name = 'Webhook';
  private baseUrl?: string;

  async initialize(config: Record<string, unknown>): Promise<void> {
    this.baseUrl = config.baseUrl as string;
    logger.info('Webhook connector initialized', { baseUrl: this.baseUrl });
  }

  async execute(action: string, params: Record<string, unknown>): Promise<IConnectorResult> {
    switch (action) {
      case 'send':
        return this.sendWebhook(params);
      default:
        return { success: false, error: `Unknown action: ${action}` };
    }
  }

  async validateConfig(config: Record<string, unknown>): Promise<boolean> {
    return typeof config.baseUrl === 'string' && config.baseUrl.startsWith('http');
  }

  getAvailableActions(): IConnectorAction[] {
    return [
      {
        name: 'send',
        description: 'Send an HTTP webhook request',
        inputSchema: { url: 'string', method: 'string', headers: 'object', body: 'object' },
        outputSchema: { statusCode: 'number', response: 'object' },
      },
    ];
  }

  async disconnect(): Promise<void> {
    this.baseUrl = undefined;
    logger.info('Webhook connector disconnected');
  }

  private async sendWebhook(params: Record<string, unknown>): Promise<IConnectorResult> {
    const url = (params.url as string) || this.baseUrl;
    logger.info('Sending webhook', { url, method: params.method || 'POST' });
    // In production, make actual HTTP request
    return {
      success: true,
      data: { statusCode: 200, response: { received: true } },
      metadata: { url, method: params.method || 'POST' },
    };
  }
}
