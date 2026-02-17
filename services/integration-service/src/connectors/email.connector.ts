import { IConnector, IConnectorResult, IConnectorAction, createLogger } from '@ai-driven/common';

const logger = createLogger('integration-service:email');

export class EmailConnector implements IConnector {
  readonly type = 'email';
  readonly name = 'Email (SMTP)';
  private smtpConfig?: Record<string, unknown>;

  async initialize(config: Record<string, unknown>): Promise<void> {
    this.smtpConfig = config;
    logger.info('Email connector initialized');
  }

  async execute(action: string, params: Record<string, unknown>): Promise<IConnectorResult> {
    switch (action) {
      case 'send_email':
        return this.sendEmail(params);
      default:
        return { success: false, error: `Unknown action: ${action}` };
    }
  }

  async validateConfig(config: Record<string, unknown>): Promise<boolean> {
    return !!(config.host && config.port && config.username && config.password);
  }

  getAvailableActions(): IConnectorAction[] {
    return [
      {
        name: 'send_email',
        description: 'Send an email via SMTP',
        inputSchema: { to: 'string', subject: 'string', body: 'string', html: 'boolean' },
        outputSchema: { messageId: 'string' },
      },
    ];
  }

  async disconnect(): Promise<void> {
    this.smtpConfig = undefined;
    logger.info('Email connector disconnected');
  }

  private async sendEmail(params: Record<string, unknown>): Promise<IConnectorResult> {
    logger.info('Sending email', { to: params.to, subject: params.subject });
    // In production, use nodemailer or similar
    return {
      success: true,
      data: { messageId: `email_${Date.now()}` },
    };
  }
}
