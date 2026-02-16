package com.aidriven.lambda;

import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Lambda handler for agent mode webhook events.
 *
 * <p>
 * Phase 1: Synchronous processing (no SQS FIFO yet).
 * <ul>
 * <li>Validates the webhook event</li>
 * <li>Classifies intent (command, question, approval, irrelevant)</li>
 * <li>Posts an ack comment</li>
 * <li>Runs the AgentOrchestrator</li>
 * <li>Updates the ack comment with the result</li>
 * </ul>
 * </p>
 *
 * <p>
 * Phase 2 will split this into AgentWebhookHandler (thin: validates + enqueues
 * to SQS FIFO)
 * and AgentProcessorHandler (heavy: runs orchestrator).
 * </p>
 */
@Slf4j
public class AgentWebhookHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String BOT_ACCOUNT_NAME = "AI Agent";

    private final ObjectMapper objectMapper;
    private final JiraClient jiraClient;
    private final ServiceFactory serviceFactory;
    private final CommentIntentClassifier classifier;
    private final JiraCommentFormatter formatter;

    /** No-arg constructor required by AWS Lambda runtime. */
    public AgentWebhookHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.jiraClient = factory.getJiraClient();
        this.serviceFactory = factory;
        this.classifier = new CommentIntentClassifier();
        this.formatter = new JiraCommentFormatter();
    }

    /** Constructor for testing. */
    public AgentWebhookHandler(ObjectMapper objectMapper, JiraClient jiraClient,
            ServiceFactory serviceFactory,
            CommentIntentClassifier classifier,
            JiraCommentFormatter formatter) {
        this.objectMapper = objectMapper;
        this.jiraClient = jiraClient;
        this.serviceFactory = serviceFactory;
        this.classifier = classifier;
        this.formatter = formatter;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        try {
            log.info("Agent webhook received event");

            // Extract webhook payload
            String body = extractBody(input);
            JsonNode payload = objectMapper.readTree(body);

            String ticketKey = extractTicketKey(payload);
            String commentBody = extractCommentBody(payload);
            String commentAuthor = extractCommentAuthor(payload);

            if (ticketKey == null || commentBody == null) {
                log.warn("Missing ticket key or comment body in webhook");
                return response(400, "Missing required fields");
            }

            // Check if agent is enabled
            if (!serviceFactory.getAppConfig().getAgentConfig().enabled()) {
                log.info("Agent is disabled, ignoring event for {}", ticketKey);
                return response(200, "Agent disabled");
            }

            // Classify intent
            boolean isBot = BOT_ACCOUNT_NAME.equalsIgnoreCase(commentAuthor);
            CommentIntent intent = classifier.classify(commentBody, isBot);

            if (intent == CommentIntent.IRRELEVANT) {
                log.info("Ignoring irrelevant comment on {}", ticketKey);
                return response(200, "Ignored — not an agent command");
            }

            log.info("Processing {} intent for ticket={} by={}", intent, ticketKey, commentAuthor);

            // Post ack comment
            String userMessage = classifier.stripMention(commentBody);
            String ackComment = formatter.formatAck(userMessage);
            String ackCommentId = jiraClient.addComment(ticketKey, ackComment);

            // Phase 2: Enqueue to SQS FIFO
            String queueUrl = serviceFactory.getAppConfig().getAgentConfig().queueUrl();
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalStateException("AGENT_QUEUE_URL is not configured");
            }

            enqueueEvent(ticketKey, userMessage, commentAuthor, ackCommentId, queueUrl);

            return response(200, "Agent task queued");

        } catch (Exception e) {
            log.error("Agent webhook error: {}", e.getMessage(), e);
            return response(500, "Internal error: " + e.getMessage());
        }
    }

    private void enqueueEvent(String ticketKey, String userMessage,
            String commentAuthor, String ackCommentId, String queueUrl) throws Exception {

        Map<String, String> message = Map.of(
                "ticketKey", ticketKey,
                "commentBody", userMessage,
                "commentAuthor", commentAuthor,
                "ackCommentId", ackCommentId);

        String messageBody = objectMapper.writeValueAsString(message);

        serviceFactory.getSqsClient().sendMessage(req -> req
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .messageGroupId(ticketKey)
                .messageDeduplicationId(ackCommentId)); // Use ack ID as dedup key

        log.info("Enqueued task for ticket={} to queue={}", ticketKey, queueUrl);
    }

    private String extractBody(Map<String, Object> input) {
        if (input.containsKey("body")) {
            return (String) input.get("body");
        }
        // Direct invocation — input IS the payload
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractTicketKey(JsonNode payload) {
        JsonNode issue = payload.path("issue");
        if (issue.has("key"))
            return issue.get("key").asText();
        return null;
    }

    private String extractCommentBody(JsonNode payload) {
        JsonNode comment = payload.path("comment");
        if (comment.has("body"))
            return comment.get("body").asText();
        return null;
    }

    private String extractCommentAuthor(JsonNode payload) {
        JsonNode author = payload.path("comment").path("author");
        if (author.has("displayName"))
            return author.get("displayName").asText();
        return "unknown";
    }

    private Map<String, Object> response(int statusCode, String message) {
        return Map.of(
                "statusCode", statusCode,
                "body", Map.of("message", message));
    }
}
