package com.aidriven.core.agent.guardrail;

/**
 * Risk classification for tool actions.
 * Determines whether human approval is required before execution.
 */
public enum RiskLevel {

    /** Read-only operations: search, get, list, add comment. Auto-execute. */
    LOW,

    /** Write operations: create branch, commit code, open PR. Execute and notify. */
    MEDIUM,

    /** Destructive operations: merge PR, delete branch, transition to Done. Requires explicit approval. */
    HIGH
}
