package com.aidriven.lambda;

import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.TicketKey;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.aidriven.lambda.factory.ServiceFactory;

/**
 * Processes Jira webhook events from SQS FIFO queue.
 *
 * Since SQS FIFO handles deduplication at the queue level,
 * this handler only processes unique events (one per ticket+labels within 5 min).
 */
@Slf4j
public class JiraWebhookProcessor implements RequestHandler<SQSEvent, Void> {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final List<String> VALID_AI_LABELS = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final JiraClient jiraClient;
    private final SfnClient sfnClient;
    private final String stateMachineArn;

    public JiraWebhookProcessor() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.jiraClient = factory.getJiraClient();
        this.sfnClient = factory.getSfnClient();
        this.stateMachineArn = factory.getAppConfig().getStateMachineArn().orElse("");
    }

    // For testing
    public JiraWebhookProcessor(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
                                JiraClient jiraClient, SfnClient sfnClient, String stateMachineArn) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.jiraClient = jiraClient;
        this.sfnClient = sfnClient;
        this.stateMachineArn = stateMachineArn;
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message, context);
            } catch (Exception e) {
                log.error("Failed to process SQS message {}: {}", message.getMessageId(), e.getMessage(), e);
                throw new RuntimeException("Failed to process message", e);
            }
        }
        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message, Context context) throws Exception {
        String body = message.getBody();
        JsonNode payload = objectMapper.readTree(body);

        JsonNode issue = payload.path("issue");
        String ticketKey = issue.path("key").asText(null);
        String ticketId = issue.path("id").asText(null);

        if (ticketKey == null || !TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
            log.warn("Invalid ticket key in SQS message");
            return;
        }

        // Defensive defaults
        if (ticketId == null || ticketId.isBlank()) {
            ticketId = ticketKey; // Use ticketKey as fallback
        }

        LoggingUtils.appendKey("ticketKey", ticketKey);
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());
        log.info("Processing webhook for ticket: {}", ticketKey);

        List<String> labels = extractLabels(payload);
        List<String> triggerLabels = labels.stream()
                .filter(VALID_AI_LABELS::contains)
                .collect(Collectors.toList());

        if (triggerLabels.isEmpty()) {
            log.info("No valid trigger labels for ticket {}", ticketKey);
            return;
        }

        OperationContext opContext = extractContext(payload);

        // Save initial state using factory method
        TicketState state = TicketState.forTicket(
                opContext.tenantId(), ticketId, ticketKey, ProcessingStatus.RECEIVED);
        ticketStateRepository.save(state);
        log.info("Saved ticket state: {} with status RECEIVED", ticketKey);

        // Start Step Functions execution
        String executionName = ticketKey + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> sfnInput = new HashMap<>();
        sfnInput.put("ticketId", ticketId);
        sfnInput.put("ticketKey", ticketKey);
        sfnInput.put("labels", labels);
        sfnInput.put("dryRun", labels.contains("dry-run") || labels.contains("test-mode"));
        sfnInput.put("context", Map.of(
                "tenantId", opContext.tenantId(),
                "userId", opContext.getUserId().orElse("system"),
                "ticketKey", ticketKey,
                "correlationId", opContext.getCorrelationId()));

        StartExecutionRequest startReq = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName)
                .input(objectMapper.writeValueAsString(sfnInput))
                .build();

        sfnClient.startExecution(startReq);
        log.info("Started Step Functions execution: {} for ticket {}", executionName, ticketKey);
    }

    private List<String> extractLabels(JsonNode payload) {
        JsonNode labelsNode = payload.path("issue").path("fields").path("labels");
        if (labelsNode.isMissingNode() || !labelsNode.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(labelsNode.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }

    private OperationContext extractContext(JsonNode payload) {
        String tenantId = "default";
        if (payload.has("baseUrl")) {
            tenantId = payload.get("baseUrl").asText()
                    .replaceAll("https?://", "")
                    .replaceAll("\\.atlassian\\.net.*", "");
        }

        String userId = "system";
        if (payload.has("user")) {
            userId = payload.get("user").path("accountId").asText("system");
        }

        String ticketKey = payload.path("issue").path("key").asText();

        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .ticketKey(TicketKey.of(ticketKey))
                .build();
    }
}
