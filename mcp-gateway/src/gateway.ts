/**
 * MCP Gateway - Routes requests to appropriate MCP providers.
 */

export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface ToolResult {
  content: Array<{ type: string; text: string }>;
  isError?: boolean;
}

export interface McpProvider {
  namespace: string;
  listTools(): Promise<ToolDefinition[]>;
  callTool(name: string, args: Record<string, unknown>): Promise<ToolResult>;
}

export class McpGateway {
  private providers: Map<string, McpProvider> = new Map();

  register(provider: McpProvider): void {
    this.providers.set(provider.namespace, provider);
    console.log(`Registered MCP provider: ${provider.namespace}`);
  }

  listNamespaces(): string[] {
    return Array.from(this.providers.keys());
  }

  async listTools(namespace: string): Promise<ToolDefinition[]> {
    const provider = this.providers.get(namespace);
    if (!provider) {
      throw new Error(`Unknown namespace: ${namespace}`);
    }
    return provider.listTools();
  }

  async callTool(
    namespace: string,
    toolName: string,
    args: Record<string, unknown>
  ): Promise<ToolResult> {
    const provider = this.providers.get(namespace);
    if (!provider) {
      throw new Error(`Unknown namespace: ${namespace}`);
    }
    return provider.callTool(toolName, args);
  }
}
