/**
 * Per-tenant configuration for multi-tenant SaaS deployments.
 *
 * Each company/client gets their own TenantConfig which controls:
 *   - Which services are enabled (pipeline, agent, or both)
 *   - Which source control platforms are available
 *   - Resource limits and AI model settings
 *   - Naming prefixes for resource isolation
 *
 * Usage:
 *   const acmeTenant = TenantConfig.create('acme', {
 *       enablePipelineMode: true,
 *       enableAgentMode: true,
 *       sourceControlPlatforms: ['GITHUB'],
 *       defaultPlatform: 'GITHUB',
 *       defaultWorkspace: 'acme-corp',
 *       defaultRepo: 'main-app',
 *   });
 */
export interface TenantLimits {
    readonly maxFileSizeChars: number;
    readonly maxTotalContextChars: number;
    readonly maxFileSizeBytes: number;
    readonly maxContextForClaude: number;
}

export interface TenantConfigProps {
    // Service toggles
    readonly enablePipelineMode?: boolean;
    readonly enableAgentMode?: boolean;

    // Source control
    readonly sourceControlPlatforms?: ('GITHUB' | 'BITBUCKET')[];
    readonly defaultPlatform?: string;
    readonly defaultWorkspace?: string;
    readonly defaultRepo?: string;

    // AI model
    readonly claudeModel?: string;
    readonly claudeMaxTokens?: number;
    readonly claudeTemperature?: number;

    // Limits
    readonly limits?: Partial<TenantLimits>;

    // Pipeline settings
    readonly mergeWaitTimeoutDays?: number;
    readonly contextMode?: string;
    readonly promptVersion?: string;
    readonly branchPrefix?: string;

    // Agent settings
    readonly agentTriggerPrefix?: string;
    readonly agentMaxTurns?: number;
    readonly agentMaxWallClockSeconds?: number;
    readonly agentGuardrailsEnabled?: boolean;
    readonly agentCostBudgetPerTicket?: number;
    readonly agentClassifierUseLlm?: boolean;
    readonly mcpServersConfig?: string;
}

export class TenantConfig {
    readonly tenantId: string;
    readonly resourcePrefix: string;

    // Service toggles
    readonly enablePipelineMode: boolean;
    readonly enableAgentMode: boolean;

    // Source control
    readonly sourceControlPlatforms: ('GITHUB' | 'BITBUCKET')[];
    readonly defaultPlatform: string;
    readonly defaultWorkspace: string;
    readonly defaultRepo: string;

    // AI model
    readonly claudeModel: string;
    readonly claudeMaxTokens: number;
    readonly claudeTemperature: number;

    // Limits
    readonly limits: TenantLimits;

    // Pipeline settings
    readonly mergeWaitTimeoutDays: number;
    readonly contextMode: string;
    readonly promptVersion: string;
    readonly branchPrefix: string;

    // Agent settings
    readonly agentTriggerPrefix: string;
    readonly agentMaxTurns: number;
    readonly agentMaxWallClockSeconds: number;
    readonly agentGuardrailsEnabled: boolean;
    readonly agentCostBudgetPerTicket: number;
    readonly agentClassifierUseLlm: boolean;
    readonly mcpServersConfig: string;

    private constructor(tenantId: string, props: TenantConfigProps) {
        this.tenantId = tenantId;
        this.resourcePrefix = `ai-driven-${tenantId}`;

        this.enablePipelineMode = props.enablePipelineMode ?? true;
        this.enableAgentMode = props.enableAgentMode ?? false;

        this.sourceControlPlatforms = props.sourceControlPlatforms ?? ['GITHUB'];
        this.defaultPlatform = props.defaultPlatform ?? 'GITHUB';
        this.defaultWorkspace = props.defaultWorkspace ?? '';
        this.defaultRepo = props.defaultRepo ?? '';

        this.claudeModel = props.claudeModel ?? 'claude-sonnet-4-20250514';
        this.claudeMaxTokens = props.claudeMaxTokens ?? 32768;
        this.claudeTemperature = props.claudeTemperature ?? 0.2;

        this.limits = {
            maxFileSizeChars: props.limits?.maxFileSizeChars ?? 100000,
            maxTotalContextChars: props.limits?.maxTotalContextChars ?? 3000000,
            maxFileSizeBytes: props.limits?.maxFileSizeBytes ?? 500000,
            maxContextForClaude: props.limits?.maxContextForClaude ?? 700000,
        };

        this.mergeWaitTimeoutDays = props.mergeWaitTimeoutDays ?? 7;
        this.contextMode = props.contextMode ?? 'FULL_REPO';
        this.promptVersion = props.promptVersion ?? 'v1';
        this.branchPrefix = props.branchPrefix ?? 'ai/';

        this.agentTriggerPrefix = props.agentTriggerPrefix ?? '@ai';
        this.agentMaxTurns = props.agentMaxTurns ?? 10;
        this.agentMaxWallClockSeconds = props.agentMaxWallClockSeconds ?? 720;
        this.agentGuardrailsEnabled = props.agentGuardrailsEnabled ?? true;
        this.agentCostBudgetPerTicket = props.agentCostBudgetPerTicket ?? 200000;
        this.agentClassifierUseLlm = props.agentClassifierUseLlm ?? false;
        this.mcpServersConfig = props.mcpServersConfig ?? '[]';
    }

    /**
     * Factory method to create a tenant configuration.
     * @param tenantId Unique tenant identifier (used in resource naming)
     * @param props Tenant-specific overrides (all optional, sensible defaults)
     */
    static create(tenantId: string, props: TenantConfigProps = {}): TenantConfig {
        if (!tenantId || !/^[a-z0-9-]+$/.test(tenantId)) {
            throw new Error(`Invalid tenantId '${tenantId}': must be lowercase alphanumeric with hyphens`);
        }
        return new TenantConfig(tenantId, props);
    }
}
