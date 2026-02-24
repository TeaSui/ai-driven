package com.aidriven.jira;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.exception.ConfigurationException;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.util.HttpResponseHandler;
import com.aidriven.core.resilience.CircuitBreaker;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.IssueTrackerProvider;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for interacting with Jira Cloud REST API.
 * Implements both IssueTrackerClient (internal) and IssueTrackerProvider (SPI).
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JiraClient implements IssueTrackerClient, IssueTrackerProvider {

    private final String baseUrl;
    private final @NonNull String authHeader;
    private final @NonNull HttpClient httpClient;
    private final @NonNull ObjectMapper objectMapper;
    private CircuitBreaker circuitBreaker;

    /** Configuration POJO for Jira */
    public record JiraSecret(String baseUrl, String email, String apiToken) {
    }

    public JiraClient(String baseUrl, String email, String apiToken) {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    public JiraClient withCircuitBreaker(CircuitBreaker breaker) {
        this.circuitBreaker = breaker;
        return this;
    }

    public static JiraClient fromSecrets(SecretsService secretsManager, String secretArn) {
        try {
            JiraSecret secret = secretsManager.getSecretAs(secretArn, JiraSecret.class);
            if (secret == null || secret.baseUrl() == null || secret.email() == null || secret.apiToken() == null) {
                throw new ConfigurationException(
                        "JiraClient: secret '" + secretArn + "' is missing required fields (baseUrl, email, apiToken)");
            }
            return new JiraClient(secret.baseUrl(), secret.email(), secret.apiToken());
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigurationException("JiraClient init failed for secret: " + secretArn, e);
        }
    }

    @Override
    public String getName() {
        return "jira";
    }

    @Override
    public TicketInfo getTicket(OperationContext context, String ticketKey) throws Exception {
        validateOperationContext(context);
        validateTicketKey(ticketKey);
        String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey);
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Jira", "getTicket",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, "Jira", "getTicket " + ticketKey);
        return parseTicket(objectMapper.readTree(response.body()));
    }

    private void validateOperationContext(OperationContext context) {
        Objects.requireNonNull(context, "OperationContext must not be null");
    }

    private void validateTicketKey(String ticketKey) {
        Objects.requireNonNull(ticketKey, "ticketKey must not be null");
        if (ticketKey.isBlank())
            throw new IllegalArgumentException("ticketKey must not be blank");
        // Jira keys typically follow PROJ-123 format.
        // We relax this to avoid crashing on GitHub PR numbers (e.g., "14")
        // during routing/fallback. If it's not a valid format, Jira API will return
        // 404/400 anyway.
        if (ticketKey.matches("^\\d+$")) {
            log.warn("Ticket key '{}' looks like a number (possibly GitHub PR). This might fail Jira API calls.",
                    ticketKey);
            return;
        }

        if (!ticketKey.contains("-") || ticketKey.startsWith("-") || ticketKey.endsWith("-")
                || !ticketKey.equals(ticketKey.toUpperCase())) {
            throw new IllegalArgumentException("Invalid ticket key format: " + ticketKey);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTicketDetails(OperationContext context, String ticketKey) {
        try {
            validateOperationContext(context);
            validateTicketKey(ticketKey);
            String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey);
            HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                    () -> httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString()), "Jira",
                    "getTicketDetails", circuitBreaker);
            HttpResponseHandler.checkResponse(response, "Jira", "getTicketDetails");
            return objectMapper.readValue(response.body(), Map.class);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException || e instanceof RuntimeException)
                throw (RuntimeException) e;
            throw new RuntimeException("Jira getTicketDetails failed", e);
        }
    }

    @Override
    public void postComment(OperationContext context, String ticketKey, String body) {
        try {
            validateOperationContext(context);
            validateTicketKey(ticketKey);
            addComment(context, ticketKey, body);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException || e instanceof RuntimeException)
                throw (RuntimeException) e;
            throw new RuntimeException("Jira addComment failed", e);
        }
    }

    @Override
    public String addComment(OperationContext context, String ticketKey, String comment) throws Exception {
        validateOperationContext(context);
        validateTicketKey(ticketKey);
        Objects.requireNonNull(comment, "comment must not be null");
        String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey) + "/comment";
        String body = objectMapper.writeValueAsString(Map.of("body", Map.of("type", "doc", "version", 1,
                "content", List.of(Map.of("type", "paragraph", "content",
                        List.of(Map.of("type", "text", "text", comment)))))));
        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Jira", "addComment",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, "Jira", "addComment");
        JsonNode json = objectMapper.readTree(response.body());
        if (!json.has("id")) {
            throw new RuntimeException("Jira addComment failed, response: " + response.body());
        }
        return json.get("id").asText();
    }

    @Override
    public void editComment(OperationContext context, String ticketKey, String commentId, String newBody)
            throws Exception {
        validateOperationContext(context);
        validateTicketKey(ticketKey);
        Objects.requireNonNull(newBody, "newBody must not be null");
        String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey) + "/comment/" + commentId;
        String body = objectMapper.writeValueAsString(Map.of("body", Map.of("type", "doc", "version", 1,
                "content", List.of(Map.of("type", "paragraph", "content",
                        List.of(Map.of("type", "text", "text", newBody)))))));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildPutRequest(url, body), HttpResponse.BodyHandlers.ofString()), "Jira",
                "editComment", circuitBreaker);
        HttpResponseHandler.checkResponse(response, "Jira", "editComment");
    }

    @Override
    public void updateLabels(OperationContext context, String ticketKey, List<String> add, List<String> remove) {
        try {
            validateOperationContext(context);
            validateTicketKey(ticketKey);
            String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey);
            List<Map<String, Object>> ops = new ArrayList<>();
            for (String l : add)
                ops.add(Map.of("add", l));
            for (String l : remove)
                ops.add(Map.of("remove", l));
            String body = objectMapper.writeValueAsString(Map.of("update", Map.of("labels", ops)));
            HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                    () -> httpClient.send(buildPutRequest(url, body), HttpResponse.BodyHandlers.ofString()), "Jira",
                    "updateLabels", circuitBreaker);
            HttpResponseHandler.checkResponse(response, "Jira", "updateLabels");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException || e instanceof RuntimeException)
                throw (RuntimeException) e;
            throw new RuntimeException("Jira updateLabels failed", e);
        }
    }

    @Override
    public List<Transition> getTransitions(OperationContext context, String ticketKey) throws Exception {
        validateOperationContext(context);
        validateTicketKey(ticketKey);
        String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey) + "/transitions";
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString()), "Jira",
                "getTransitions", circuitBreaker);
        HttpResponseHandler.checkResponse(response, "Jira", "getTransitions");
        JsonNode json = objectMapper.readTree(response.body());
        List<Transition> transitions = new ArrayList<>();
        if (json.has("transitions")) {
            for (JsonNode t : json.get("transitions")) {
                transitions.add(new Transition(t.get("id").asText(), t.get("to").get("name").asText()));
            }
        }
        return transitions;
    }

    @Override
    public void updateStatus(OperationContext context, String ticketKey, String statusName) {
        try {
            validateOperationContext(context);
            validateTicketKey(ticketKey);
            Objects.requireNonNull(statusName, "statusName must not be null");
            updateStatus_internal(context, ticketKey, statusName);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException || e instanceof NullPointerException
                    || e instanceof IllegalStateException || e instanceof RuntimeException)
                throw (RuntimeException) e;
            throw new RuntimeException("Jira updateStatus failed", e);
        }
    }

    private void updateStatus_internal(OperationContext context, String ticketKey, String statusName) throws Exception {
        List<Transition> transitions = getTransitions(context, ticketKey);
        String transitionId = null;
        for (Transition t : transitions) {
            if (t.toStatus().equalsIgnoreCase(statusName)) {
                transitionId = t.id();
                break;
            }
        }
        if (transitionId == null) {
            throw new IllegalStateException(
                    String.format("Transition to '%s' not available for ticket %s", statusName, ticketKey));
        }
        transitionTicket(context, ticketKey, transitionId);
    }

    @Override
    public void transitionTicket(OperationContext context, String ticketKey, String transitionId) throws Exception {
        validateOperationContext(context);
        validateTicketKey(ticketKey);
        String url = baseUrl + "/rest/api/3/issue/" + encode(ticketKey) + "/transitions";
        String body = objectMapper.writeValueAsString(Map.of("transition", Map.of("id", transitionId)));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildPostRequest(url, body), HttpResponse.BodyHandlers.ofString()), "Jira",
                "transitionTicket", circuitBreaker);
        HttpResponseHandler.checkResponse(response, "Jira", "transitionTicket");
    }

    // --- Helpers ---
    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
                .header("Accept", "application/json").GET().build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest buildPutRequest(String url, String body) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    private TicketInfo parseTicket(JsonNode json) {
        JsonNode fields = json.get("fields");
        List<String> labels = new java.util.ArrayList<>();
        if (fields.has("labels") && fields.get("labels").isArray()) {
            for (JsonNode l : fields.get("labels")) {
                labels.add(l.asText());
            }
        }

        String priority = fields.has("priority") && !fields.get("priority").isNull()
                ? fields.get("priority").path("name").asText("")
                : "";

        return TicketInfo.builder()
                .ticketKey(json.get("key").asText())
                .summary(fields.get("summary").asText())
                .status(fields.has("status") && !fields.get("status").isNull()
                        ? fields.get("status").get("name").asText()
                        : "")
                .description(fields.has("description") && !fields.get("description").isNull()
                        ? fields.get("description").toString()
                        : "")
                .labels(labels)
                .priority(priority)
                .build();
    }
}
