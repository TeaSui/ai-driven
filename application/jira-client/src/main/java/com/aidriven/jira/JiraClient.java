package com.aidriven.jira;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.util.HttpResponseHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for interacting with Jira Cloud REST API.
 * Implements {@link IssueTrackerClient} for platform-agnostic issue tracking.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class JiraClient implements IssueTrackerClient {

    private final String baseUrl;
    private final @NonNull String authHeader;
    private final @NonNull HttpClient httpClient;
    private final @NonNull ObjectMapper objectMapper;

    // Constructor for backward compatibility (pre-encoding authHeader)
    public JiraClient(String baseUrl, String email, String apiToken) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(apiToken, "apiToken must not be null");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a JiraClient from secrets.
     */
    public static JiraClient fromSecrets(SecretsService secretsManager, String secretArn) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> secrets = secretsManager.getSecretJson(secretArn);

            String baseUrl = getRequiredSecret(secrets, "baseUrl");
            String apiToken = getRequiredSecret(secrets, "apiToken");
            String userEmail = getRequiredSecret(secrets, "email");

            String auth = userEmail + ":" + apiToken;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            return new JiraClient(baseUrl, "Basic " + encodedAuth, HttpClient.newHttpClient(), mapper);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JiraClient from secrets", e);
        }
    }

    private static String getRequiredSecret(Map<String, Object> secrets, String key) {
        Object val = secrets.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required key '" + key + "' in Jira secret");
        }
        return val.toString();
    }

    /**
     * Fetches a ticket by its key (e.g., "PROJ-123").
     */
    public TicketInfo getTicket(String ticketKey) throws Exception {
        validateTicketKey(ticketKey);
        String url = baseUrl + "/rest/api/3/issue/" + encodePathSegment(ticketKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "Jira", "getTicket " + ticketKey);

        JsonNode json = objectMapper.readTree(response.body());
        return parseTicket(json);
    }

    /**
     * Updates the status of a ticket by transitioning to a new status.
     */
    public void transitionTicket(String ticketKey, String transitionId) throws Exception {
        validateTicketKey(ticketKey);
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        String url = baseUrl + "/rest/api/3/issue/" + encodePathSegment(ticketKey) + "/transitions";

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("transition", java.util.Map.of("id", transitionId)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 204 No Content is expected for successful transitions
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Jira", "transitionTicket " + ticketKey);
        }

        log.info("Transitioned ticket {} to status with transition ID {}", ticketKey, transitionId);
    }

    /**
     * Updates the status of a ticket by finding and executing the transition to the
     * target status.
     * This is a convenience method that automatically looks up the transition ID.
     *
     * @param ticketKey        The ticket key (e.g., "PROJ-123")
     * @param targetStatusName The name of the status to transition to (e.g., "In
     *                         Review", "Done")
     * @throws IllegalStateException if no transition to the target status is
     *                               available
     */
    public void updateStatus(String ticketKey, String targetStatusName) throws Exception {
        validateTicketKey(ticketKey);
        Objects.requireNonNull(targetStatusName, "targetStatusName must not be null");

        List<Transition> transitions = getTransitions(ticketKey);
        Transition targetTransition = transitions.stream()
                .filter(t -> t.toStatus().equalsIgnoreCase(targetStatusName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Transition to '%s' not available for ticket %s. Available transitions: %s",
                                targetStatusName, ticketKey,
                                transitions.stream().map(Transition::toStatus).toList())));

        transitionTicket(ticketKey, targetTransition.id());
        log.info("Updated ticket {} status to {}", ticketKey, targetStatusName);
    }

    /**
     * Gets available transitions for a ticket.
     */
    public List<Transition> getTransitions(String ticketKey) throws Exception {
        validateTicketKey(ticketKey);
        String url = baseUrl + "/rest/api/3/issue/" + encodePathSegment(ticketKey) + "/transitions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "Jira", "getTransitions " + ticketKey);

        JsonNode json = objectMapper.readTree(response.body());
        List<Transition> transitions = new ArrayList<>();

        for (JsonNode t : json.get("transitions")) {
            transitions.add(new Transition(
                    t.get("id").asText(),
                    t.get("name").asText(),
                    t.get("to").get("name").asText()));
        }

        return transitions;
    }

    /**
     * Adds a comment to a ticket.
     *
     * @return The ID of the created comment.
     */
    @Override
    public String addComment(String ticketKey, String comment) throws Exception {
        validateTicketKey(ticketKey);
        Objects.requireNonNull(comment, "comment must not be null");
        String url = baseUrl + "/rest/api/3/issue/" + encodePathSegment(ticketKey) + "/comment";

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "body", java.util.Map.of(
                        "type", "doc",
                        "version", 1,
                        "content", List.of(java.util.Map.of(
                                "type", "paragraph",
                                "content", List.of(java.util.Map.of(
                                        "type", "text",
                                        "text", comment)))))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 201 Created is expected for new comments
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Jira", "addComment " + ticketKey);
        }

        JsonNode json = objectMapper.readTree(response.body());
        String commentId = json.get("id").asText();
        log.info("Added comment {} to ticket {}", commentId, ticketKey);
        return commentId;
    }

    /**
     * Edits an existing comment on a ticket (replaces content in-place).
     * Uses PUT /rest/api/3/issue/{issueKey}/comment/{commentId}
     */
    @Override
    public void editComment(String ticketKey, String commentId, String newBody) throws Exception {
        validateTicketKey(ticketKey);
        Objects.requireNonNull(commentId, "commentId must not be null");
        Objects.requireNonNull(newBody, "newBody must not be null");

        String url = baseUrl + "/rest/api/3/issue/" + encodePathSegment(ticketKey) + "/comment/" + commentId;

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "body", java.util.Map.of(
                        "type", "doc",
                        "version", 1,
                        "content", List.of(java.util.Map.of(
                                "type", "paragraph",
                                "content", List.of(java.util.Map.of(
                                        "type", "text",
                                        "text", newBody)))))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Jira", "editComment " + ticketKey + "/" + commentId);
        }
        log.info("Edited comment {} on ticket {}", commentId, ticketKey);
    }

    private TicketInfo parseTicket(JsonNode json) {
        JsonNode fields = json.get("fields");

        List<String> labels = new ArrayList<>();
        if (fields.has("labels")) {
            for (JsonNode label : fields.get("labels")) {
                labels.add(label.asText());
            }
        }

        return TicketInfo.builder()
                .ticketId(json.get("id").asText())
                .ticketKey(json.get("key").asText())
                .projectKey(extractProjectKey(json.get("key").asText()))
                .summary(fields.has("summary") ? fields.get("summary").asText() : "")
                .description(extractDescription(fields.get("description")))
                .labels(labels)
                .status(fields.has("status") ? fields.get("status").get("name").asText() : "")
                .priority(fields.has("priority") && !fields.get("priority").isNull()
                        ? fields.get("priority").get("name").asText()
                        : "")
                .createdAt(fields.has("created") ? parseJiraDate(fields.get("created").asText()) : Instant.now())
                .updatedAt(fields.has("updated") ? parseJiraDate(fields.get("updated").asText()) : Instant.now())
                .build();
    }

    private String extractProjectKey(String ticketKey) {
        if (ticketKey == null || !ticketKey.contains("-")) {
            return ticketKey != null ? ticketKey : "";
        }
        return ticketKey.split("-")[0];
    }

    private String extractDescription(JsonNode description) {
        if (description == null || description.isNull()) {
            return "";
        }
        // For Atlassian Document Format (ADF), extract text content
        StringBuilder sb = new StringBuilder();
        extractTextFromAdf(description, sb);
        return sb.toString().trim();
    }

    private void extractTextFromAdf(JsonNode node, StringBuilder sb) {
        if (node == null)
            return;

        if (node.has("text")) {
            sb.append(node.get("text").asText());
        }

        if (node.has("content")) {
            for (JsonNode child : node.get("content")) {
                extractTextFromAdf(child, sb);
            }
        }
    }

    private Instant parseJiraDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return Instant.now();
        }
        try {
            return Instant.parse(dateStr);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                        .toInstant();
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse date: {}", dateStr);
                return Instant.now();
            }
        }
    }

    public record Transition(String id, String name, String toStatus) {
    }

    private static final java.util.regex.Pattern TICKET_KEY_PATTERN = java.util.regex.Pattern
            .compile("^[A-Z][A-Z0-9]+-\\d+$");

    private void validateTicketKey(String ticketKey) {
        Objects.requireNonNull(ticketKey, "ticketKey must not be null");
        if (!TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
            throw new IllegalArgumentException("Invalid ticket key format: " + ticketKey);
        }
    }

    private String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
