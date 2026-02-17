import { IConnector, IConnectorResult, IConnectorAction, createLogger } from '@ai-driven/common';

const logger = createLogger('integration-service:slack');

export class SlackConnector implements IConnector {
  readonly type = 'slack';
  readonly name = 'Slack';
  private webhookUrl?: string;

  async initialize(config: Record<string, unknown>): Promise<void> {
    this.webhookUrl = config.webhookUrl as string;
    logger.info('Slack connector initialized');
  }

  async execute(action: string, params: Record<string, unknown>): Promise<IConnectorResult> {
    switch (action) {
      case 'send_message':
        return this.sendMessage(params);
      case 'send_notification':
        return this.sendNotification(params);
      default:
        return { success: false, error: `Unknown action: ${action}` };
    }
  }

  async validateConfig(config: Record<string, unknown>): Promise<boolean> {
    return typeof config.webhookUrl === 'string' && config.webhookUrl.startsWith('https://hooks.slack.com/');
  }

  getAvailableActions(): IConnectorAction[] {
    return [
      {
        name: 'send_message',
        description: 'Send a message to a Slack channel',
        inputSchema: { channel: 'string', message: 'string' },
        outputSchema: { messageId: 'string' },
      },
      {
        name: 'send_notification',
        description: 'Send a notification to a Slack channel',
        inputSchema: { channel: 'string', title: 'string', body: 'string' },
        outputSchema: { messageId: 'string' },
      },
    ];
  }

  async disconnect(): Promise<void> {
    this.webhookUrl = undefined;
    logger.info('Slack connector disconnected');
  }

  private async sendMessage(params: Record<string, unknown>): Promise<IConnectorResult> {
    logger.info('Sending Slack message', { channel: params.channel });
    // In production, make HTTP request to Slack webhook
    return {
      success: true,
      data: { messageId: `slack_msg_${Date.now()}`, channel: params.channel },
    };
  }

  private async sendNotification(params: Record<string, unknown>): Promise<IConnectorResult> {
    logger.info('Sending Slack notification', { channel: params.channel, title: params.title });
    return {
      success: true,
      data: { messageId: `slack_notif_${Date.now()}`, channel: params.channel },
    };
  }
}
