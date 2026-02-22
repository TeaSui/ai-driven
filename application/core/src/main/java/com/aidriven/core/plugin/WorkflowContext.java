package com.aidriven.core.plugin;

import com.aidriven.core.model.TicketInfo;
import lombok.Builder;
import lombok.Data;
import lombok.With;

import java.util.Map;

/**
 * Immutable context object passed through the workflow pipeline.
 * Plugins can read and return modified copies via {@code with*} methods.
 */
@Data
@Builder(toBuilder = true)
public class WorkflowContext {

    /** The Jira ticket being processed. */
    private final TicketInfo ticket;

    /** The code context string (file tree + source files). */
    private final String codeContext;

    /** The S3 key where code context is stored. */
    private final String codeContextS3Key;

    /** The resolved target branch. */
    private final String targetBranch;

    /** The resolved platform (BITBUCKET or GITHUB). */
    private final String platform;

    /** The resolved repository owner. */
    private final String repoOwner;

    /** The resolved repository slug. */
    private final String repoSlug;

    /** Whether this is a dry-run execution. */
    private final boolean dryRun;

    /** Additional plugin-specific metadata. */
    @Builder.Default
    private final Map<String, Object> metadata = Map.of();

    /**
     * Returns a copy of this context with additional metadata.
     */
    public WorkflowContext withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new java.util.HashMap<>(metadata);
        newMetadata.put(key, value);
        return this.toBuilder().metadata(java.util.Collections.unmodifiableMap(newMetadata)).build();
    }
}
