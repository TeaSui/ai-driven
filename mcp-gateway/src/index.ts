import { APIGatewayProxyEvent, APIGatewayProxyResult, Context } from 'aws-lambda';
import { SecretsManagerClient, GetSecretValueCommand } from '@aws-sdk/client-secrets-manager';
import { McpGateway } from './gateway';
import { Context7Provider } from './providers/context7';
import { GitHubProvider } from './providers/github';
import { JiraProvider } from './providers/jira';

// Initialize gateway with providers
const gateway = new McpGateway();
const secretsClient = new SecretsManagerClient({});

// Cache for secrets (Lambda container reuse)
const secretsCache: Map<string, Record<string, string>> = new Map();

// Register providers (lazy initialization)
let initialized = false;

async function getSecret(arn: string): Promise<Record<string, string>> {
  if (secretsCache.has(arn)) {
    return secretsCache.get(arn)!;
  }

  try {
    const response = await secretsClient.send(new GetSecretValueCommand({ SecretId: arn }));
    const secret = JSON.parse(response.SecretString || '{}');
    secretsCache.set(arn, secret);
    return secret;
  } catch (error) {
    console.error(`Failed to fetch secret ${arn}:`, error);
    return {};
  }
}

async function initializeProviders(): Promise<void> {
  if (initialized) return;

  // Context7 - Documentation lookup (no credentials needed)
  if (process.env.CONTEXT7_ENABLED !== 'false') {
    gateway.register(new Context7Provider());
    console.log('Registered Context7 provider');
  }

  // GitHub - Repository operations
  const githubSecretArn = process.env.GITHUB_SECRET_ARN;
  if (githubSecretArn) {
    try {
      const githubCreds = await getSecret(githubSecretArn);
      if (githubCreds.token) {
        gateway.register(new GitHubProvider({
          token: githubCreds.token,
          owner: githubCreds.owner,
          repo: githubCreds.repo,
        }));
        console.log('Registered GitHub provider');
      }
    } catch (error) {
      console.error('Failed to initialize GitHub provider:', error);
    }
  }

  // Jira - Issue tracking
  const jiraSecretArn = process.env.JIRA_SECRET_ARN;
  if (jiraSecretArn) {
    try {
      const jiraCreds = await getSecret(jiraSecretArn);
      if (jiraCreds.apiToken && jiraCreds.baseUrl) {
        gateway.register(new JiraProvider({
          baseUrl: jiraCreds.baseUrl,
          email: jiraCreds.email || '',
          apiToken: jiraCreds.apiToken,
        }));
        console.log('Registered Jira provider');
      }
    } catch (error) {
      console.error('Failed to initialize Jira provider:', error);
    }
  }

  initialized = true;
  console.log(`MCP Gateway initialized with providers: ${gateway.listNamespaces().join(', ')}`);
}

/**
 * Lambda handler for MCP Gateway.
 *
 * Endpoints:
 *   GET  /health              - Health check
 *   GET  /namespaces          - List available namespaces
 *   GET  /{namespace}/tools   - List tools for a namespace
 *   POST /{namespace}/call    - Call a tool
 */
export async function handler(
  event: APIGatewayProxyEvent,
  context: Context
): Promise<APIGatewayProxyResult> {
  console.log(`MCP Gateway request: ${event.httpMethod} ${event.path}`);

  try {
    await initializeProviders();

    const path = event.path || '/';
    const method = event.httpMethod;

    // Health check
    if (path === '/health' || path === '/') {
      return success({
        status: 'healthy',
        namespaces: gateway.listNamespaces(),
        timestamp: new Date().toISOString()
      });
    }

    // List namespaces
    if (path === '/namespaces' && method === 'GET') {
      return success({ namespaces: gateway.listNamespaces() });
    }

    // Parse namespace from path: /{namespace}/tools or /{namespace}/call
    const pathParts = path.split('/').filter(p => p);
    if (pathParts.length < 2) {
      return error(400, 'Invalid path. Use /{namespace}/tools or /{namespace}/call');
    }

    const namespace = pathParts[0];
    const action = pathParts[1];

    // List tools
    if (action === 'tools' && method === 'GET') {
      const tools = await gateway.listTools(namespace);
      return success({ namespace, tools });
    }

    // Call tool
    if (action === 'call' && method === 'POST') {
      const body = parseBody(event);
      if (!body.tool || typeof body.tool !== 'string') {
        return error(400, 'Missing required field: tool');
      }

      const toolName = body.tool as string;
      const args = (body.arguments || {}) as Record<string, unknown>;
      const result = await gateway.callTool(namespace, toolName, args);
      return success({ namespace, tool: toolName, result });
    }

    return error(404, `Unknown action: ${action}`);

  } catch (err) {
    console.error('MCP Gateway error:', err);
    const message = err instanceof Error ? err.message : 'Unknown error';
    return error(500, message);
  }
}

function parseBody(event: APIGatewayProxyEvent): Record<string, unknown> {
  if (!event.body) return {};
  try {
    const body = event.isBase64Encoded
      ? Buffer.from(event.body, 'base64').toString('utf-8')
      : event.body;
    return JSON.parse(body);
  } catch {
    return {};
  }
}

function success(data: unknown): APIGatewayProxyResult {
  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  };
}

function error(statusCode: number, message: string): APIGatewayProxyResult {
  return {
    statusCode,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: message }),
  };
}
