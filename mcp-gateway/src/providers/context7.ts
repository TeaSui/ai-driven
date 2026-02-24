import { McpProvider, ToolDefinition, ToolResult } from '../gateway';

const CONTEXT7_API_BASE = 'https://context7.com/api';

/**
 * Context7 MCP Provider - Documentation lookup for libraries and frameworks.
 *
 * Tools:
 *   - resolve-library-id: Find library ID from name
 *   - get-library-docs: Get documentation for a library
 */
export class Context7Provider implements McpProvider {
  namespace = 'context7';

  async listTools(): Promise<ToolDefinition[]> {
    return [
      {
        name: 'resolve-library-id',
        description: 'Resolves a package/library name to a Context7-compatible library ID',
        inputSchema: {
          type: 'object',
          properties: {
            libraryName: {
              type: 'string',
              description: 'Library name to search for (e.g., "react", "express", "langchain")',
            },
          },
          required: ['libraryName'],
        },
      },
      {
        name: 'get-library-docs',
        description: 'Retrieves documentation for a library by its Context7 library ID',
        inputSchema: {
          type: 'object',
          properties: {
            libraryId: {
              type: 'string',
              description: 'Context7 library ID (e.g., "/vercel/next.js")',
            },
            topic: {
              type: 'string',
              description: 'Optional topic to focus on (e.g., "routing", "authentication")',
            },
          },
          required: ['libraryId'],
        },
      },
    ];
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    try {
      switch (name) {
        case 'resolve-library-id':
          return await this.resolveLibraryId(args.libraryName as string);
        case 'get-library-docs':
          return await this.getLibraryDocs(
            args.libraryId as string,
            args.topic as string | undefined
          );
        default:
          return this.errorResult(`Unknown tool: ${name}`);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error';
      return this.errorResult(`Context7 error: ${message}`);
    }
  }

  private async resolveLibraryId(libraryName: string): Promise<ToolResult> {
    if (!libraryName) {
      return this.errorResult('libraryName is required');
    }

    const response = await fetch(`${CONTEXT7_API_BASE}/v1/search?q=${encodeURIComponent(libraryName)}`, {
      headers: { 'Accept': 'application/json' },
    });

    if (!response.ok) {
      return this.errorResult(`Context7 API error: ${response.status}`);
    }

    const data = await response.json() as { results?: Array<{ id: string; name: string; description?: string }> };
    const results = data.results || [];

    if (results.length === 0) {
      return this.textResult(`No libraries found for "${libraryName}"`);
    }

    const formatted = results.slice(0, 5).map((lib: { id: string; name: string; description?: string }) =>
      `- **${lib.name}** (${lib.id}): ${lib.description || 'No description'}`
    ).join('\n');

    return this.textResult(`Found libraries for "${libraryName}":\n${formatted}`);
  }

  private async getLibraryDocs(libraryId: string, topic?: string): Promise<ToolResult> {
    if (!libraryId) {
      return this.errorResult('libraryId is required');
    }

    const url = topic
      ? `${CONTEXT7_API_BASE}/v1/docs${libraryId}?topic=${encodeURIComponent(topic)}`
      : `${CONTEXT7_API_BASE}/v1/docs${libraryId}`;

    const response = await fetch(url, {
      headers: { 'Accept': 'application/json' },
    });

    if (!response.ok) {
      return this.errorResult(`Context7 API error: ${response.status}`);
    }

    const data = await response.json() as { content?: string; sections?: Array<{ title: string; content: string }> };

    if (data.content) {
      return this.textResult(data.content);
    }

    if (data.sections && data.sections.length > 0) {
      const formatted = data.sections.map((s: { title: string; content: string }) =>
        `## ${s.title}\n${s.content}`
      ).join('\n\n');
      return this.textResult(formatted);
    }

    return this.textResult('No documentation found');
  }

  private textResult(text: string): ToolResult {
    return { content: [{ type: 'text', text }] };
  }

  private errorResult(message: string): ToolResult {
    return { content: [{ type: 'text', text: message }], isError: true };
  }
}
