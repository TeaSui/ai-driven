package com.aidriven.core.agent.guardrail;

/**
 * Policy decision for a tool call based on its risk level.
 *
 * @param level           The assessed risk level
 * @param requiresApproval Whether human approval is needed before execution
 * @param approvalPrompt  Human-readable description of the action awaiting approval
 */
public record ActionPolicy(
        RiskLevel level,
        boolean requiresApproval,
        String approvalPrompt) {

    /** Create an auto-execute policy (LOW/MEDIUM risk). */
    public static ActionPolicy autoExecute(RiskLevel level) {
        return new ActionPolicy(level, false, null);
    }

    /** Create a policy requiring human approval (HIGH risk). */
    public static ActionPolicy requireApproval(RiskLevel level, String prompt) {
        return new ActionPolicy(level, true, prompt);
    }
}
