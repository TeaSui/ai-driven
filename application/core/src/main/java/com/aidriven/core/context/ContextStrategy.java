package com.aidriven.core.context;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;

/**
 * Strategy interface for building code context.
 * Updated for multi-tenancy via OperationContext.
 */
public interface ContextStrategy {

    /**
     * Builds a context string for the given ticket.
     * 
     * @param context Security context for the tenant
     * @param ticket  Information about the Jira ticket
     * @param branch  The git branch to fetch context from
     * @return A markdown-formatted string or null if strategy fails.
     */
    String buildContext(OperationContext context, TicketInfo ticket, BranchName branch);
}
