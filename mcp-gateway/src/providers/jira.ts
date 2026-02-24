import { McpProvider, ToolDefinition, ToolResult } from '../gateway';

export interface JiraConfig {
  baseUrl: string;
  email: string;
  apiToken: string;
}

/**
 * Jira MCP Provider - Issue tracking operations.
 *
 * Tools:
 *   - get-issue: Get issue details
 *   - search-issues: Search issues using JQL
 *   - create-issue: Create a new issue
 *   - update-issue: Update an existing issue
 *   - add-comment: Add a comment to an issue
 *   - get-transitions: Get available transitions for an issue
 *   - transition-issue: Transition an issue to a new status
 *   - list-projects: List available projects
 */
export class JiraProvider implements McpProvider {
  namespace = 'jira';
  private config: JiraConfig;

  constructor(config: JiraConfig) {
    this.config = config;
  }

  async listTools(): Promise<ToolDefinition[]> {
    return [
      {
        name: 'get-issue',
        description: 'Get details of a Jira issue',
        inputSchema: {
          type: 'object',
          properties: {
            issueKey: { type: 'string', description: 'Issue key (e.g., PROJ-123)' },
            fields: { type: 'array', items: { type: 'string' }, description: 'Fields to include' },
          },
          required: ['issueKey'],
        },
      },
      {
        name: 'search-issues',
        description: 'Search issues using JQL',
        inputSchema: {
          type: 'object',
          properties: {
            jql: { type: 'string', description: 'JQL query' },
            maxResults: { type: 'number', description: 'Max results (default: 50)' },
            fields: { type: 'array', items: { type: 'string' }, description: 'Fields to include' },
          },
          required: ['jql'],
        },
      },
      {
        name: 'create-issue',
        description: 'Create a new Jira issue',
        inputSchema: {
          type: 'object',
          properties: {
            projectKey: { type: 'string', description: 'Project key' },
            issueType: { type: 'string', description: 'Issue type (e.g., Task, Bug, Story)' },
            summary: { type: 'string', description: 'Issue summary' },
            description: { type: 'string', description: 'Issue description' },
            labels: { type: 'array', items: { type: 'string' }, description: 'Labels' },
          },
          required: ['projectKey', 'issueType', 'summary'],
        },
      },
      {
        name: 'update-issue',
        description: 'Update an existing issue',
        inputSchema: {
          type: 'object',
          properties: {
            issueKey: { type: 'string', description: 'Issue key' },
            summary: { type: 'string', description: 'New summary' },
            description: { type: 'string', description: 'New description' },
            labels: { type: 'array', items: { type: 'string' }, description: 'New labels' },
          },
          required: ['issueKey'],
        },
      },
      {
        name: 'add-comment',
        description: 'Add a comment to an issue',
        inputSchema: {
          type: 'object',
          properties: {
            issueKey: { type: 'string', description: 'Issue key' },
            body: { type: 'string', description: 'Comment body' },
          },
          required: ['issueKey', 'body'],
        },
      },
      {
        name: 'get-transitions',
        description: 'Get available transitions for an issue',
        inputSchema: {
          type: 'object',
          properties: {
            issueKey: { type: 'string', description: 'Issue key' },
          },
          required: ['issueKey'],
        },
      },
      {
        name: 'transition-issue',
        description: 'Transition an issue to a new status',
        inputSchema: {
          type: 'object',
          properties: {
            issueKey: { type: 'string', description: 'Issue key' },
            transitionId: { type: 'string', description: 'Transition ID' },
            comment: { type: 'string', description: 'Optional comment' },
          },
          required: ['issueKey', 'transitionId'],
        },
      },
      {
        name: 'list-projects',
        description: 'List available Jira projects',
        inputSchema: {
          type: 'object',
          properties: {
            maxResults: { type: 'number', description: 'Max results' },
          },
        },
      },
    ];
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    try {
      switch (name) {
        case 'get-issue':
          return await this.getIssue(args);
        case 'search-issues':
          return await this.searchIssues(args);
        case 'create-issue':
          return await this.createIssue(args);
        case 'update-issue':
          return await this.updateIssue(args);
        case 'add-comment':
          return await this.addComment(args);
        case 'get-transitions':
          return await this.getTransitions(args);
        case 'transition-issue':
          return await this.transitionIssue(args);
        case 'list-projects':
          return await this.listProjects(args);
        default:
          return this.errorResult(`Unknown tool: ${name}`);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error';
      return this.errorResult(`Jira error: ${message}`);
    }
  }

  private async jiraFetch(endpoint: string, options: RequestInit = {}): Promise<Response> {
    const url = `${this.config.baseUrl}/rest/api/3${endpoint}`;
    const auth = Buffer.from(`${this.config.email}:${this.config.apiToken}`).toString('base64');

    return fetch(url, {
      ...options,
      headers: {
        'Authorization': `Basic ${auth}`,
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        ...options.headers,
      },
    });
  }

  private async getIssue(args: Record<string, unknown>): Promise<ToolResult> {
    const issueKey = args.issueKey as string;
    const fields = args.fields as string[] | undefined;

    let url = `/issue/${issueKey}`;
    if (fields && fields.length > 0) {
      url += `?fields=${fields.join(',')}`;
    }

    const response = await this.jiraFetch(url);
    if (!response.ok) {
      return this.errorResult(`Failed to get issue: ${response.status}`);
    }

    const data = await response.json() as { key: string; fields: { summary: string; status: { name: string }; description?: unknown } };

    // Format for readability
    const formatted = {
      key: data.key,
      summary: data.fields?.summary,
      status: data.fields?.status?.name,
      description: data.fields?.description,
    };

    return this.textResult(JSON.stringify(formatted, null, 2));
  }

  private async searchIssues(args: Record<string, unknown>): Promise<ToolResult> {
    const jql = args.jql as string;
    const maxResults = (args.maxResults as number) || 50;
    const fields = args.fields as string[] | undefined;

    const body: Record<string, unknown> = { jql, maxResults };
    if (fields) body.fields = fields;

    const response = await this.jiraFetch('/search', {
      method: 'POST',
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      return this.errorResult(`Search failed: ${response.status}`);
    }

    const data = await response.json() as { issues: Array<{ key: string; fields: { summary: string; status: { name: string } } }> };
    const issues = data.issues || [];

    const formatted = issues.map((issue: { key: string; fields: { summary: string; status: { name: string } } }) =>
      `- **${issue.key}**: ${issue.fields?.summary} (${issue.fields?.status?.name})`
    ).join('\n');

    return this.textResult(`Found ${issues.length} issues:\n${formatted}`);
  }

  private async createIssue(args: Record<string, unknown>): Promise<ToolResult> {
    const fields: Record<string, unknown> = {
      project: { key: args.projectKey },
      issuetype: { name: args.issueType },
      summary: args.summary,
    };

    if (args.description) {
      fields.description = {
        type: 'doc',
        version: 1,
        content: [{ type: 'paragraph', content: [{ type: 'text', text: args.description }] }],
      };
    }

    if (args.labels) {
      fields.labels = args.labels;
    }

    const response = await this.jiraFetch('/issue', {
      method: 'POST',
      body: JSON.stringify({ fields }),
    });

    if (!response.ok) {
      const error = await response.text();
      return this.errorResult(`Failed to create issue: ${error}`);
    }

    const data = await response.json() as { key: string; self: string };
    return this.textResult(`Issue created: ${data.key}\n${this.config.baseUrl}/browse/${data.key}`);
  }

  private async updateIssue(args: Record<string, unknown>): Promise<ToolResult> {
    const issueKey = args.issueKey as string;
    const fields: Record<string, unknown> = {};

    if (args.summary) fields.summary = args.summary;
    if (args.labels) fields.labels = args.labels;
    if (args.description) {
      fields.description = {
        type: 'doc',
        version: 1,
        content: [{ type: 'paragraph', content: [{ type: 'text', text: args.description }] }],
      };
    }

    const response = await this.jiraFetch(`/issue/${issueKey}`, {
      method: 'PUT',
      body: JSON.stringify({ fields }),
    });

    if (!response.ok) {
      return this.errorResult(`Failed to update issue: ${response.status}`);
    }

    return this.textResult(`Issue ${issueKey} updated successfully`);
  }

  private async addComment(args: Record<string, unknown>): Promise<ToolResult> {
    const issueKey = args.issueKey as string;
    const body = args.body as string;

    const response = await this.jiraFetch(`/issue/${issueKey}/comment`, {
      method: 'POST',
      body: JSON.stringify({
        body: {
          type: 'doc',
          version: 1,
          content: [{ type: 'paragraph', content: [{ type: 'text', text: body }] }],
        },
      }),
    });

    if (!response.ok) {
      return this.errorResult(`Failed to add comment: ${response.status}`);
    }

    const data = await response.json() as { id: string };
    return this.textResult(`Comment added to ${issueKey} (id: ${data.id})`);
  }

  private async getTransitions(args: Record<string, unknown>): Promise<ToolResult> {
    const issueKey = args.issueKey as string;

    const response = await this.jiraFetch(`/issue/${issueKey}/transitions`);
    if (!response.ok) {
      return this.errorResult(`Failed to get transitions: ${response.status}`);
    }

    const data = await response.json() as { transitions: Array<{ id: string; name: string }> };
    const transitions = data.transitions || [];

    const formatted = transitions.map((t: { id: string; name: string }) => `- ${t.id}: ${t.name}`).join('\n');
    return this.textResult(`Available transitions:\n${formatted}`);
  }

  private async transitionIssue(args: Record<string, unknown>): Promise<ToolResult> {
    const issueKey = args.issueKey as string;
    const transitionId = args.transitionId as string;
    const comment = args.comment as string | undefined;

    const body: Record<string, unknown> = {
      transition: { id: transitionId },
    };

    if (comment) {
      body.update = {
        comment: [{
          add: {
            body: {
              type: 'doc',
              version: 1,
              content: [{ type: 'paragraph', content: [{ type: 'text', text: comment }] }],
            },
          },
        }],
      };
    }

    const response = await this.jiraFetch(`/issue/${issueKey}/transitions`, {
      method: 'POST',
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      return this.errorResult(`Failed to transition issue: ${response.status}`);
    }

    return this.textResult(`Issue ${issueKey} transitioned successfully`);
  }

  private async listProjects(args: Record<string, unknown>): Promise<ToolResult> {
    const maxResults = (args.maxResults as number) || 50;

    const response = await this.jiraFetch(`/project/search?maxResults=${maxResults}`);
    if (!response.ok) {
      return this.errorResult(`Failed to list projects: ${response.status}`);
    }

    const data = await response.json() as { values: Array<{ key: string; name: string }> };
    const projects = data.values || [];

    const formatted = projects.map((p: { key: string; name: string }) => `- ${p.key}: ${p.name}`).join('\n');
    return this.textResult(`Projects:\n${formatted}`);
  }

  private textResult(text: string): ToolResult {
    return { content: [{ type: 'text', text }] };
  }

  private errorResult(message: string): ToolResult {
    return { content: [{ type: 'text', text: message }], isError: true };
  }
}
