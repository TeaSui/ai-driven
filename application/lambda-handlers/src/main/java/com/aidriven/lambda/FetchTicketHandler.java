package com.aidriven.lambda;

import com.aidriven.core.config.AppConfig;
import com.aidriven.core.model.AgentType;
import com.aidriven.core.model.ModelSelector;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.PlatformResolver;
import com.aidriven.core.source.RepositoryResolver;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.aidriven.lambda.factory.ServiceFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Lambda handler for fetching ticket details from Jira.
 */
@Slf4j
@RequiredArgsConstructor
public class FetchTicketHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final TicketStateRepository ticketStateRepository;
    private final JiraClient jiraClient;
    private final AppConfig appConfig;

    /** No-arg constructor required by AWS Lambda runtime. */
    public FetchTicketHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.jiraClient = factory.getJiraClient();
        this.appConfig = factory.getAppConfig();
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {

        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");

        LoggingUtils.appendKey("ticketKey", ticketKey);
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());

        log.info("Fetching ticket details for: {}", ticketKey);

        try {
            TicketInfo ticket = jiraClient.getTicket(ticketKey);
            AgentType agentType = ticket.determineAgentType();
            boolean dryRun = ticket.isDryRun();

            log.info("Ticket fetched — agentType: {}, dryRun: {}", agentType, dryRun);

            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.ANALYZING)
                            .withAgentType(agentType.getValue()));

            return buildOutput(ticketId, ticketKey, ticket, agentType, dryRun);

        } catch (Exception e) {
            log.error("Failed to fetch ticket details for: {}", ticketKey, e);
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.FAILED).withError(e.getMessage()));
            throw new RuntimeException("Failed to fetch ticket details", e);
        } finally {
            // Context cleared by Powertools
        }
    }

    private Map<String, Object> buildOutput(String id, String key, TicketInfo ticket, AgentType agent, boolean dryRun) {
        String resolvedModel = ModelSelector.resolve(ticket.getLabels(), null);
        Platform platform = PlatformResolver.resolve(ticket.getLabels(), null, appConfig.getDefaultPlatform());
        RepositoryResolver.ResolvedRepository repo = RepositoryResolver.resolve(
                ticket.getLabels(), null, appConfig.getDefaultWorkspace(), appConfig.getDefaultRepo(),
                appConfig.getDefaultPlatform());

        log.info("Resolved platform: {}, repo: {}", platform,
                repo != null ? repo.owner() + "/" + repo.repo() : "default");

        Map<String, Object> out = new HashMap<>();
        out.put("ticketId", id);
        out.put("ticketKey", key);
        out.put("agentType", agent.getValue());
        out.put("summary", ticket.getSummary());
        out.put("description", ticket.getDescription());
        out.put("labels", ticket.getLabels());
        out.put("priority", ticket.getPriority() != null ? ticket.getPriority() : "");
        out.put("dryRun", dryRun);
        out.put("platform", platform.name());

        if (repo != null) {
            out.put("repoOwner", repo.owner());
            out.put("repoSlug", repo.repo());
        }
        if (resolvedModel != null) {
            out.put("resolvedModel", resolvedModel);
        }
        return out;
    }
}
