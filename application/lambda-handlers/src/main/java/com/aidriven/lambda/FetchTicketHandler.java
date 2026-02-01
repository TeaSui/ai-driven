package com.aidriven.lambda;

import com.aidriven.core.model.AgentType;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.SecretsService;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.Map;

/**
 * Lambda handler for fetching ticket details from Jira.
 * Used as the first step in the linear workflow to retrieve full ticket
 * information including labels, priority, and description.
 */
@Slf4j
public class FetchTicketHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final TicketStateRepository ticketStateRepository;
    private final SecretsService secretsService;
    private final String jiraSecretArn;

    public FetchTicketHandler() {
        this.ticketStateRepository = new TicketStateRepository(
                DynamoDbClient.create(),
                System.getenv("DYNAMODB_TABLE_NAME"));
        this.secretsService = new SecretsService(SecretsManagerClient.create());
        this.jiraSecretArn = System.getenv("JIRA_SECRET_ARN");
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");

        log.info("Fetching ticket details for: {}", ticketKey);

        try {
            // Fetch full ticket details from Jira
            JiraClient jiraClient = JiraClient.fromSecrets(secretsService, jiraSecretArn);
            TicketInfo ticket = jiraClient.getTicket(ticketKey);

            // Determine agent type based on labels
            AgentType agentType = ticket.determineAgentType();
            boolean dryRun = ticket.isDryRun();
            log.info("Ticket details fetched — agentType: {}, dryRun: {} for ticket: {}", agentType, dryRun, ticketKey);

            // Update state in DynamoDB
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.ANALYZING)
                            .withAgentType(agentType.getValue()));

            // Return output for Step Functions
            return Map.of(
                    "ticketId", ticketId,
                    "ticketKey", ticketKey,
                    "agentType", agentType.getValue(),
                    "summary", ticket.getSummary(),
                    "description", ticket.getDescription(),
                    "labels", ticket.getLabels(),
                    "priority", ticket.getPriority() != null ? ticket.getPriority() : "",
                    "dryRun", dryRun);

        } catch (Exception e) {
            log.error("Failed to fetch ticket details for: {}", ticketKey, e);

            // Update state to FAILED
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.FAILED)
                            .withError(e.getMessage()));

            throw new RuntimeException("Failed to fetch ticket details", e);
        }
    }
}
