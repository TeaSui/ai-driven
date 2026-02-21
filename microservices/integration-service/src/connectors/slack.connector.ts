import { WebClient } from '@slack/web-api';
import { BaseConnector, ConnectorTestResult } from './base.connector';

export class SlackConnector extends BaseConnector {
  private client: WebClient;

  constructor(config: Record<string, unknown>, credentials: Record<string, string>) {
    super(config, credentials);
    this.client = new WebClient(this.getCredential('botToken'));
  }

  async test(): Promise<ConnectorTestResult> {
    try {
      await this.client.auth.test();
      return { success: true, message: 'Slack connection successful' };
    } catch (error) {
      return { success: false, message: (error as Error).message };
    }
  }

  async execute(
    action: string,
    params: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    switch (action) {
      case 'sendMessage':
        return this.sendMessage(params);
      case 'sendDirectMessage':
        return this.sendDirectMessage(params);
      case 'createChannel':
        return this.createChannel(params);
      default:
        throw new Error(`Unknown Slack action: ${action}`);
    }
  }

  private async sendMessage(
    params: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    const result = await this.client.chat.postMessage({
      channel: params.channel as string,
      text: params.text as string,
      blocks: params.blocks as never[],
    });
    return { ts: result.ts, channel: result.channel, ok: result.ok };
  }

  private async sendDirectMessage(
    params: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    const openResult = await this.client.conversations.open({
      users: params.userId as string,
    });
    const channelId = openResult.channel?.id;
    if (!channelId) throw new Error('Failed to open DM channel');

    const result = await this.client.chat.postMessage({
      channel: channelId,
      text: params.text as string,
    });
    return { ts: result.ts, channel: result.channel, ok: result.ok };
  }

  private async createChannel(
    params: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    const result = await this.client.conversations.create({
      name: params.name as string,
      is_private: params.isPrivate as boolean,
    });
    return {
      channelId: result.channel?.id,
      channelName: result.channel?.name,
      ok: result.ok,
    };
  }
}
