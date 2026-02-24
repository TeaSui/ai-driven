package com.aidriven.tool.context;

import com.aidriven.core.config.AppConfig;
import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Service to manage code context gathering using different strategies with
 * multi-tenancy support.
 */
@Slf4j
@RequiredArgsConstructor
public class ContextService {

    private final ContextStrategy smartStrategy;
    private final ContextStrategy fullRepoStrategy;
    private final AppConfig.ContextMode configuredMode;

    public String buildContext(OperationContext context, TicketInfo ticket, BranchName branch) {
        // 1. Explicit Label Overrides
        if (ticket.isSmartContext()) {
            log.info("Manual SMART context requested via label for ticket: {}", ticket.getTicketKey());
            String result = smartStrategy.buildContext(context, ticket, branch);
            if (result != null) {
                return result;
            }
            log.info("Smart strategy returned null, falling back to Full Repo strategy");
            return fullRepoStrategy.buildContext(context, ticket, branch);
        }

        if (ticket.isFullRepo()) {
            log.info("Manual FULL_REPO context requested via label for ticket: {}", ticket.getTicketKey());
            return fullRepoStrategy.buildContext(context, ticket, branch);
        }

        // 2. Configured Default
        if (configuredMode == AppConfig.ContextMode.INCREMENTAL) {
            log.info("Using configured INCREMENTAL context mode for ticket: {}", ticket.getTicketKey());
            String result = smartStrategy.buildContext(context, ticket, branch);
            if (result != null) {
                return result;
            }
            log.info("Smart strategy returned null, falling back to Full Repo strategy");
            return fullRepoStrategy.buildContext(context, ticket, branch);
        }

        // Default: FULL_REPO
        log.info("Using default FULL_REPO context mode for ticket: {}", ticket.getTicketKey());
        return fullRepoStrategy.buildContext(context, ticket, branch);
    }
}
