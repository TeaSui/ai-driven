import axios from 'axios';
import { WebhookConnector } from '../../connectors/webhook.connector';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

describe('WebhookConnector', () => {
  const config = { url: 'https://example.com/webhook', method: 'POST' };
  const credentials = {};
  let connector: WebhookConnector;

  beforeEach(() => {
    connector = new WebhookConnector(config, credentials);
    jest.clearAllMocks();
  });

  describe('test', () => {
    it('should return success when webhook is reachable', async () => {
      (mockedAxios.post as jest.Mock).mockResolvedValue({ status: 200 });
      const result = await connector.test();
      expect(result.success).toBe(true);
    });

    it('should return failure when webhook is unreachable', async () => {
      (mockedAxios.post as jest.Mock).mockRejectedValue(new Error('Connection refused'));
      const result = await connector.test();
      expect(result.success).toBe(false);
      expect(result.message).toContain('Connection refused');
    });
  });

  describe('execute', () => {
    it('should send webhook and return response', async () => {
      (mockedAxios as unknown as jest.Mock).mockResolvedValue({
        status: 200,
        statusText: 'OK',
        data: { received: true },
      });

      const result = await connector.execute('send', { key: 'value' });
      expect(result.status).toBe(200);
      expect(result.data).toEqual({ received: true });
    });

    it('should throw for unknown action', async () => {
      await expect(connector.execute('unknown', {})).rejects.toThrow('Unknown webhook action');
    });
  });
});
