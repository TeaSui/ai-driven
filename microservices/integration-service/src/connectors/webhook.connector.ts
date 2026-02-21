import axios from 'axios';
import { BaseConnector, ConnectorTestResult } from './base.connector';

export class WebhookConnector extends BaseConnector {
  async test(): Promise<ConnectorTestResult> {
    try {
      const url = this.getConfig<string>('url');
      await axios.post(
        url,
        { type: 'ping', timestamp: new Date().toISOString() },
        {
          timeout: 5000,
          headers: this.getConfig<Record<string, string>>('headers', {}),
        }
      );
      return { success: true, message: 'Webhook endpoint reachable' };
    } catch (error) {
      return { success: false, message: (error as Error).message };
    }
  }

  async execute(
    action: string,
    params: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    if (action !== 'send') throw new Error(`Unknown webhook action: ${action}`);

    const url = this.getConfig<string>('url');
    const method = this.getConfig<string>('method', 'POST').toLowerCase();
    const headers = {
      'Content-Type': 'application/json',
      ...this.getConfig<Record<string, string>>('headers', {}),
    };

    const response = await axios({
      method: method as 'get' | 'post' | 'put' | 'patch' | 'delete',
      url,
      headers,
      data: params,
      timeout: 30000,
    });

    return {
      status: response.status,
      statusText: response.statusText,
      data: response.data,
    };
  }
}
