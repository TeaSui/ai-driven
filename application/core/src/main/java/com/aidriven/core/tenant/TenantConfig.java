package com.aidriven.core.tenant;

import com.aidriven.core.config.McpServerConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB entity representing a tenant (client company) configuration.
 *
 * <p>Key schema (single-table design):
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 *
 * <p>Each tenant can independently configure:
 * <ul>
 *   <li>Which issue tracker to use (Jira, Linear, GitHub Issues)</li>
 *   <li>Which source control platform (GitHub, Bitbucket, GitLab)</li>
 *   <li>Which AI model and token budget</li>
 *   <li>Which optional tool modules are enabled</li>
 *   <li>Which MCP servers to connect to</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class TenantConfig {

    private String pk;
    private String sk;

    /** Unique tenant identifier (e.g., "acme-corp"). */
    private String tenantId;

    /** Human-readable company name. */
    private String displayName;

    /** Subscription plan: FREE, STARTER, PROFESSIONAL, ENTERPRISE. */
    private String plan;

    /** Whether this tenant is active. */
    private boolean active;

    // --- Issue Tracker ---

    /** Issue tracker type: JIRA, LINEAR, GITHUB_ISSUES. */
    private String issueTrackerType;

    /** AWS Secrets Manager ARN for issue tracker credentials. */
    private String issueTrackerSecretArn;

    // --- Source Control ---

    /** Source control type: GITHUB, BITBUCKET, GITLAB. */
    private String sourceControlType;

    /** AWS Secrets Manager ARN for source control credentials. */
    private String sourceControlSecretArn;

    /** Default workspace/organization for this tenant. */
    private String defaultWorkspace;

    /** Default repository for this tenant. */
    private String defaultRepo;

    // --- AI Engine ---

    /** Claude model to use (overrides global default). */
    private String aiModel;

    /** Maximum tokens per month for this tenant. */
    private Long maxTokensPerMonth;

    /** Maximum tokens per ticket conversation. */
    private Integer maxTokensPerTicket;

    // --- Enabled Modules ---

    /**
     * List of enabled tool module namespaces.
     * e.g., ["source_control", "issue_tracker", "code_context", "monitoring"]
     */
    private List<String> enabledModules;

    // --- MCP Servers ---

    /** JSON-serialized list of McpServerConfig for this tenant. */
    private String mcpServersJson;

    // --- Metadata ---

    private Instant createdAt;
    private Instant updatedAt;

    /** TTL for DynamoDB auto-cleanup (epoch seconds). Null = no expiry. */
    private Long ttl;

    // --- DynamoDB key annotations ---

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() {
        return pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() {
        return sk;
    }

    // --- Factory methods ---

    /** Creates the partition key for a tenant. */
    public static String createPk(String tenantId) {
        return "TENANT#" + tenantId;
    }

    /** The fixed sort key for tenant config records. */
    public static final String CONFIG_SK = "CONFIG";

    /**
     * Creates a new TenantConfig with required fields.
     */
    public static TenantConfig create(String tenantId, String displayName, String plan) {
        Instant now = Instant.now();
        return TenantConfig.builder()
                .pk(createPk(tenantId))
                .sk(CONFIG_SK)
                .tenantId(tenantId)
                .displayName(displayName)
                .plan(plan)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns true if the given module namespace is enabled for this tenant.
     */
    public boolean isModuleEnabled(String namespace) {
        if (enabledModules == null || enabledModules.isEmpty()) {
            // Default: core modules always enabled
            return "source_control".equals(namespace)
                    || "issue_tracker".equals(namespace)
                    || "code_context".equals(namespace);
        }
        return enabledModules.contains(namespace);
    }

    /**
     * Returns the effective AI model, falling back to the provided default.
     */
    public String effectiveAiModel(String globalDefault) {
        return (aiModel != null && !aiModel.isBlank()) ? aiModel : globalDefault;
    }

    /**
     * Returns the effective max tokens per ticket, falling back to the provided default.
     */
    public int effectiveMaxTokensPerTicket(int globalDefault) {
        return (maxTokensPerTicket != null && maxTokensPerTicket > 0) ? maxTokensPerTicket : globalDefault;
    }
}
