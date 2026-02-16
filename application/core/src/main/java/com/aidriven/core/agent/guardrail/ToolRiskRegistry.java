package com.aidriven.core.agent.guardrail;

import com.aidriven.core.agent.tool.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Maps tool names to risk levels using pattern-based rules.
 * Supports both default patterns and custom overrides.
 *
 * <p>Default risk classification:
 * <ul>
 *   <li>LOW: read-only operations (*_get_*, *_search_*, *_list_*, add_comment)</li>
 *   <li>MEDIUM: write operations (*_create_*, *_commit_*, *_update_*)</li>
 *   <li>HIGH: destructive operations (*_merge_*, *_delete_*, update_status to "Done")</li>
 * </ul>
 */
@Slf4j
public class ToolRiskRegistry {

    private static final List<RiskRule> DEFAULT_RULES = List.of(
            // HIGH risk — destructive / irreversible
            new RiskRule("_merge_", null, RiskLevel.HIGH),
            new RiskRule("_delete_", null, RiskLevel.HIGH),

            // MEDIUM risk — write operations
            new RiskRule("_create_branch", null, RiskLevel.MEDIUM),
            new RiskRule("_commit_files", null, RiskLevel.MEDIUM),
            new RiskRule("_create_pr", null, RiskLevel.MEDIUM),
            new RiskRule("_update_status", null, RiskLevel.MEDIUM),

            // LOW risk — read-only (catch-all patterns)
            new RiskRule("_get_", null, RiskLevel.LOW),
            new RiskRule("_search_", null, RiskLevel.LOW),
            new RiskRule("_list_", null, RiskLevel.LOW),
            new RiskRule("_add_comment", null, RiskLevel.LOW)
    );

    private final List<RiskRule> rules;
    private final Map<String, RiskLevel> exactOverrides;

    public ToolRiskRegistry() {
        this(List.of(), Map.of());
    }

    /**
     * @param additionalRules Extra rules evaluated before defaults
     * @param exactOverrides  Exact tool name → risk level overrides (highest priority)
     */
    public ToolRiskRegistry(List<RiskRule> additionalRules, Map<String, RiskLevel> exactOverrides) {
        List<RiskRule> allRules = new ArrayList<>(additionalRules);
        allRules.addAll(DEFAULT_RULES);
        this.rules = Collections.unmodifiableList(allRules);
        this.exactOverrides = Map.copyOf(exactOverrides);
    }

    /**
     * Assess the risk level of a tool call.
     *
     * @param call The tool call to assess
     * @return The risk level (defaults to MEDIUM for unknown tools)
     */
    public RiskLevel assess(ToolCall call) {
        String toolName = call.name();

        // 1. Exact overrides (highest priority)
        if (exactOverrides.containsKey(toolName)) {
            RiskLevel level = exactOverrides.get(toolName);
            log.debug("Tool {} risk: {} (exact override)", toolName, level);
            return level;
        }

        // 2. Context-sensitive rules (e.g., update_status to "Done" = HIGH)
        RiskLevel contextual = assessContextual(call);
        if (contextual != null) {
            log.debug("Tool {} risk: {} (contextual)", toolName, contextual);
            return contextual;
        }

        // 3. Pattern-based rules
        for (RiskRule rule : rules) {
            if (toolName.contains(rule.pattern())) {
                log.debug("Tool {} risk: {} (pattern: {})", toolName, rule.riskLevel(), rule.pattern());
                return rule.riskLevel();
            }
        }

        // 4. Default: MEDIUM for unknown tools (conservative)
        log.info("Tool {} has no risk rule — defaulting to MEDIUM", toolName);
        return RiskLevel.MEDIUM;
    }

    /**
     * Build an ActionPolicy for a tool call.
     * HIGH-risk tools require approval; others auto-execute.
     */
    public ActionPolicy buildPolicy(ToolCall call) {
        RiskLevel level = assess(call);
        if (level == RiskLevel.HIGH) {
            String prompt = buildApprovalPrompt(call);
            return ActionPolicy.requireApproval(level, prompt);
        }
        return ActionPolicy.autoExecute(level);
    }

    /**
     * Context-sensitive risk assessment.
     * Example: issue_tracker_update_status with targetStatus="Done" is HIGH risk.
     */
    private RiskLevel assessContextual(ToolCall call) {
        if (call.name().endsWith("_update_status")) {
            JsonNode input = call.input();
            if (input != null && input.has("status")) {
                String status = input.get("status").asText().toLowerCase();
                if (status.contains("done") || status.contains("closed") || status.contains("resolved")) {
                    return RiskLevel.HIGH;
                }
            }
        }
        return null;
    }

    private String buildApprovalPrompt(ToolCall call) {
        String toolName = call.name();
        JsonNode input = call.input();

        if (toolName.contains("merge")) {
            String pr = input != null && input.has("pr_id") ? input.get("pr_id").asText() : "unknown";
            return String.format("Merge PR %s into target branch", pr);
        }
        if (toolName.contains("delete")) {
            String target = input != null && input.has("branch_name")
                    ? input.get("branch_name").asText()
                    : "resource";
            return String.format("Delete %s", target);
        }
        if (toolName.contains("update_status")) {
            String status = input != null && input.has("status") ? input.get("status").asText() : "unknown";
            return String.format("Transition ticket to '%s'", status);
        }

        return String.format("Execute high-risk action: %s", toolName);
    }

    /**
     * A risk classification rule based on tool name pattern matching.
     *
     * @param pattern   Substring to match in tool name
     * @param namespace Optional namespace filter (null = match all namespaces)
     * @param riskLevel The risk level to assign when matched
     */
    public record RiskRule(String pattern, String namespace, RiskLevel riskLevel) {
    }
}
