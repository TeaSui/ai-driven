import { McpProvider, ToolDefinition, ToolResult } from '../gateway';

const GITHUB_API_BASE = 'https://api.github.com';

export interface GitHubConfig {
  token: string;
  owner?: string;
  repo?: string;
}

/**
 * GitHub MCP Provider - Repository and code operations.
 *
 * Tools:
 *   - search-repositories: Search for repositories
 *   - get-file-contents: Get contents of a file
 *   - search-code: Search code across repositories
 *   - get-repository: Get repository information
 *   - list-branches: List branches in a repository
 *   - create-or-update-file: Create or update a file
 *   - create-pull-request: Create a pull request
 *   - list-pull-requests: List pull requests
 */
export class GitHubProvider implements McpProvider {
  namespace = 'github';
  private config: GitHubConfig;

  constructor(config: GitHubConfig) {
    this.config = config;
  }

  async listTools(): Promise<ToolDefinition[]> {
    return [
      {
        name: 'search-repositories',
        description: 'Search for GitHub repositories',
        inputSchema: {
          type: 'object',
          properties: {
            query: { type: 'string', description: 'Search query' },
            page: { type: 'number', description: 'Page number (default: 1)' },
            perPage: { type: 'number', description: 'Results per page (default: 10, max: 100)' },
          },
          required: ['query'],
        },
      },
      {
        name: 'get-file-contents',
        description: 'Get the contents of a file from a repository',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
            path: { type: 'string', description: 'File path' },
            branch: { type: 'string', description: 'Branch name (default: main)' },
          },
          required: ['owner', 'repo', 'path'],
        },
      },
      {
        name: 'search-code',
        description: 'Search for code across repositories',
        inputSchema: {
          type: 'object',
          properties: {
            query: { type: 'string', description: 'Search query' },
            page: { type: 'number', description: 'Page number' },
            perPage: { type: 'number', description: 'Results per page' },
          },
          required: ['query'],
        },
      },
      {
        name: 'get-repository',
        description: 'Get information about a repository',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
          },
          required: ['owner', 'repo'],
        },
      },
      {
        name: 'list-branches',
        description: 'List branches in a repository',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
          },
          required: ['owner', 'repo'],
        },
      },
      {
        name: 'create-or-update-file',
        description: 'Create or update a file in a repository',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
            path: { type: 'string', description: 'File path' },
            content: { type: 'string', description: 'File content' },
            message: { type: 'string', description: 'Commit message' },
            branch: { type: 'string', description: 'Branch name' },
            sha: { type: 'string', description: 'SHA of file being replaced (for updates)' },
          },
          required: ['owner', 'repo', 'path', 'content', 'message'],
        },
      },
      {
        name: 'create-pull-request',
        description: 'Create a pull request',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
            title: { type: 'string', description: 'PR title' },
            body: { type: 'string', description: 'PR description' },
            head: { type: 'string', description: 'Source branch' },
            base: { type: 'string', description: 'Target branch' },
          },
          required: ['owner', 'repo', 'title', 'head', 'base'],
        },
      },
      {
        name: 'list-pull-requests',
        description: 'List pull requests in a repository',
        inputSchema: {
          type: 'object',
          properties: {
            owner: { type: 'string', description: 'Repository owner' },
            repo: { type: 'string', description: 'Repository name' },
            state: { type: 'string', enum: ['open', 'closed', 'all'], description: 'PR state filter' },
          },
          required: ['owner', 'repo'],
        },
      },
    ];
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    try {
      switch (name) {
        case 'search-repositories':
          return await this.searchRepositories(args);
        case 'get-file-contents':
          return await this.getFileContents(args);
        case 'search-code':
          return await this.searchCode(args);
        case 'get-repository':
          return await this.getRepository(args);
        case 'list-branches':
          return await this.listBranches(args);
        case 'create-or-update-file':
          return await this.createOrUpdateFile(args);
        case 'create-pull-request':
          return await this.createPullRequest(args);
        case 'list-pull-requests':
          return await this.listPullRequests(args);
        default:
          return this.errorResult(`Unknown tool: ${name}`);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error';
      return this.errorResult(`GitHub error: ${message}`);
    }
  }

  private async githubFetch(endpoint: string, options: RequestInit = {}): Promise<Response> {
    const url = endpoint.startsWith('http') ? endpoint : `${GITHUB_API_BASE}${endpoint}`;
    return fetch(url, {
      ...options,
      headers: {
        'Authorization': `Bearer ${this.config.token}`,
        'Accept': 'application/vnd.github.v3+json',
        'User-Agent': 'MCP-Gateway',
        ...options.headers,
      },
    });
  }

  private async searchRepositories(args: Record<string, unknown>): Promise<ToolResult> {
    const query = args.query as string;
    const page = (args.page as number) || 1;
    const perPage = Math.min((args.perPage as number) || 10, 100);

    const response = await this.githubFetch(
      `/search/repositories?q=${encodeURIComponent(query)}&page=${page}&per_page=${perPage}`
    );
    const data = await response.json() as { items?: Array<{ full_name: string; description?: string; html_url: string; stargazers_count: number }> };

    const items = data.items || [];
    const formatted = items.map((repo: { full_name: string; description?: string; html_url: string; stargazers_count: number }) =>
      `- **${repo.full_name}** (${repo.stargazers_count} stars)\n  ${repo.description || 'No description'}\n  ${repo.html_url}`
    ).join('\n\n');

    return this.textResult(`Found ${items.length} repositories:\n\n${formatted}`);
  }

  private async getFileContents(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;
    const path = args.path as string;
    const branch = args.branch as string;

    let url = `/repos/${owner}/${repo}/contents/${path}`;
    if (branch) url += `?ref=${branch}`;

    const response = await this.githubFetch(url);
    if (!response.ok) {
      return this.errorResult(`Failed to get file: ${response.status}`);
    }

    const data = await response.json() as { content?: string; encoding?: string };
    if (data.content && data.encoding === 'base64') {
      const content = Buffer.from(data.content, 'base64').toString('utf-8');
      return this.textResult(content);
    }

    return this.textResult(JSON.stringify(data, null, 2));
  }

  private async searchCode(args: Record<string, unknown>): Promise<ToolResult> {
    const query = args.query as string;
    const page = (args.page as number) || 1;
    const perPage = Math.min((args.perPage as number) || 10, 100);

    const response = await this.githubFetch(
      `/search/code?q=${encodeURIComponent(query)}&page=${page}&per_page=${perPage}`
    );
    const data = await response.json() as { items?: Array<{ path: string; repository: { full_name: string }; html_url: string }> };

    const items = data.items || [];
    const formatted = items.map((item: { path: string; repository: { full_name: string }; html_url: string }) =>
      `- ${item.repository.full_name}: ${item.path}\n  ${item.html_url}`
    ).join('\n');

    return this.textResult(`Found ${items.length} code results:\n\n${formatted}`);
  }

  private async getRepository(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;

    const response = await this.githubFetch(`/repos/${owner}/${repo}`);
    const data = await response.json();

    return this.textResult(JSON.stringify(data, null, 2));
  }

  private async listBranches(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;

    const response = await this.githubFetch(`/repos/${owner}/${repo}/branches`);
    const branches = await response.json() as Array<{ name: string }>;

    const formatted = branches.map((b: { name: string }) => `- ${b.name}`).join('\n');
    return this.textResult(`Branches:\n${formatted}`);
  }

  private async createOrUpdateFile(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;
    const path = args.path as string;
    const content = Buffer.from(args.content as string).toString('base64');
    const message = args.message as string;
    const branch = args.branch as string;
    const sha = args.sha as string;

    const body: Record<string, unknown> = { message, content };
    if (branch) body.branch = branch;
    if (sha) body.sha = sha;

    const response = await this.githubFetch(`/repos/${owner}/${repo}/contents/${path}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    });

    const data = await response.json();
    return this.textResult(JSON.stringify(data, null, 2));
  }

  private async createPullRequest(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;

    const response = await this.githubFetch(`/repos/${owner}/${repo}/pulls`, {
      method: 'POST',
      body: JSON.stringify({
        title: args.title,
        body: args.body,
        head: args.head,
        base: args.base,
      }),
    });

    const data = await response.json() as { html_url?: string; number?: number };
    if (data.html_url) {
      return this.textResult(`Pull request created: ${data.html_url}`);
    }
    return this.textResult(JSON.stringify(data, null, 2));
  }

  private async listPullRequests(args: Record<string, unknown>): Promise<ToolResult> {
    const owner = (args.owner as string) || this.config.owner;
    const repo = (args.repo as string) || this.config.repo;
    const state = (args.state as string) || 'open';

    const response = await this.githubFetch(`/repos/${owner}/${repo}/pulls?state=${state}`);
    const prs = await response.json() as Array<{ number: number; title: string; state: string; html_url: string; user: { login: string } }>;

    const formatted = prs.map((pr: { number: number; title: string; state: string; html_url: string; user: { login: string } }) =>
      `- #${pr.number}: ${pr.title} (${pr.state}) by ${pr.user.login}\n  ${pr.html_url}`
    ).join('\n');

    return this.textResult(`Pull requests:\n${formatted}`);
  }

  private textResult(text: string): ToolResult {
    return { content: [{ type: 'text', text }] };
  }

  private errorResult(message: string): ToolResult {
    return { content: [{ type: 'text', text: message }], isError: true };
  }
}
