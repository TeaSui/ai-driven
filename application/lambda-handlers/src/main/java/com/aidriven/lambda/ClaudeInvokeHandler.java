package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.claude.PromptBuilder;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.CodeContextS3Service;
import com.aidriven.core.service.SecretsService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import com.aidriven.core.util.LambdaInputValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lambda handler for direct Claude API invocation in the linear workflow.
 * Reads code context from S3 (written by BitbucketFetchHandler) and builds
 * a comprehensive prompt for Claude.
 */
@Slf4j
public class ClaudeInvokeHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    /**
     * Max chars of code context to send to Claude.
     * Claude Opus 4.6 has ~200K token context window. At ~4 chars/token,
     * 700K chars ≈ 175K tokens, leaving room for system prompt + response.
     * The full repo is always stored in S3; this only limits what gets sent to Claude.
     */
    private static final int MAX_CONTEXT_FOR_CLAUDE = 700_000;

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final SecretsService secretsService;
    private final String claudeSecretArn;
    private final CodeContextS3Service codeContextS3Service;

    public ClaudeInvokeHandler() {
        this.objectMapper = new ObjectMapper();
        this.ticketStateRepository = new TicketStateRepository(
                DynamoDbClient.create(),
                System.getenv("DYNAMODB_TABLE_NAME"));

        this.secretsService = new SecretsService(SecretsManagerClient.create());
        this.claudeSecretArn = System.getenv("CLAUDE_SECRET_ARN");

        String bucketName = System.getenv("CODE_CONTEXT_BUCKET");
        this.codeContextS3Service = new CodeContextS3Service(bucketName);
    }

    // Constructor for testing
    ClaudeInvokeHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            SecretsService secretsService, String claudeSecretArn,
            CodeContextS3Service codeContextS3Service) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.secretsService = secretsService;
        this.claudeSecretArn = claudeSecretArn;
        this.codeContextS3Service = codeContextS3Service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        LambdaInputValidator.requireNonEmptyInput(input, "ClaudeInvokeHandler");

        String ticketId = LambdaInputValidator.requireString(input, "ticketId");
        String ticketKey = LambdaInputValidator.requireString(input, "ticketKey");
        boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));

        log.info("ClaudeInvokeHandler processing ticket: {} (dryRun={})", ticketKey, dryRun);

        try {
            TicketInfo ticket = TicketInfo.builder()
                    .ticketId(ticketId)
                    .ticketKey(ticketKey)
                    .summary((String) input.get("summary"))
                    .description((String) input.get("description"))
                    .labels((List<String>) input.get("labels"))
                    .priority((String) input.get("priority"))
                    .build();

            updateState(ticketId, ticketKey, ProcessingStatus.GENERATING);

            // Read code context from S3 (stored by BitbucketFetchHandler)
            String codeContext = "";
            String s3Key = (String) input.get("codeContextS3Key");
            if (s3Key != null && !s3Key.isEmpty()) {
                try {
                    codeContext = codeContextS3Service.retrieveContext(s3Key);
                    log.info("Loaded code context from S3: {} ({} chars)", s3Key, codeContext.length());

                    // Truncate if context exceeds Claude's effective window
                    if (codeContext.length() > MAX_CONTEXT_FOR_CLAUDE) {
                        log.warn("Code context ({} chars) exceeds Claude limit ({} chars), truncating",
                                codeContext.length(), MAX_CONTEXT_FOR_CLAUDE);
                        codeContext = codeContext.substring(0, MAX_CONTEXT_FOR_CLAUDE)
                                + "\n\n... [context truncated - full repo available in S3]";
                    }
                } catch (Exception e) {
                    log.warn("Failed to read code context from S3 key {}: {}", s3Key, e.getMessage());
                }
            } else {
                log.warn("No codeContextS3Key in input, proceeding without code context");
            }

            // Build prompts
            String systemPrompt = PromptBuilder.backendAgentSystemPrompt();
            String userMessage = buildUserMessage(ticket, codeContext);

            log.info("Invoking Claude with prompt length: {} chars", userMessage.length());

            // Call Claude API
            String claudeApiKey = secretsService.getSecretString(claudeSecretArn);
            if (claudeApiKey == null || claudeApiKey.isBlank() || !claudeApiKey.startsWith("sk-")) {
                throw new RuntimeException("Invalid Claude API key - check secret: " + claudeSecretArn);
            }

            ClaudeClient claudeClient = new ClaudeClient(claudeApiKey, "claude-opus-4-6");
            String response = claudeClient.chat(systemPrompt, userMessage);

            AgentResult result = parseClaudeResponse(response, ticketId);

            log.info("Claude generated {} files for ticket: {}",
                    result.getGeneratedFiles() != null ? result.getGeneratedFiles().size() : 0, ticketKey);

            return Map.of(
                    "ticketId", ticketId,
                    "ticketKey", ticketKey,
                    "success", result.isSuccess(),
                    "dryRun", dryRun,
                    "files",
                    result.getGeneratedFiles() != null ? objectMapper.writeValueAsString(result.getGeneratedFiles())
                            : "[]",
                    "commitMessage", Objects.toString(result.getCommitMessage(), "feat: auto-generated changes"),
                    "prTitle", Objects.toString(result.getPrTitle(), "Auto-generated PR"),
                    "prDescription", Objects.toString(result.getPrDescription(), "Auto-generated by AI-Driven system"),
                    "agentType", "claude-opus");

        } catch (Exception e) {
            log.error("ClaudeInvokeHandler failed for ticket: {}", ticketKey, e);
            updateState(ticketId, ticketKey, ProcessingStatus.FAILED);
            throw new RuntimeException("Claude invocation failed", e);
        }
    }

    /**
     * Builds the user message with code context from S3 prepended.
     */
    private String buildUserMessage(TicketInfo ticket, String codeContext) {
        StringBuilder msg = new StringBuilder();

        if (!codeContext.isEmpty()) {
            msg.append(codeContext);
            msg.append("\n\n---\n\n");
        }

        msg.append(PromptBuilder.buildUserMessage(ticket));
        return msg.toString();
    }

    private AgentResult parseClaudeResponse(String response, String ticketId) {
        try {
            int jsonStart = response.indexOf("{");
            if (jsonStart < 0) {
                log.error("No JSON found in Claude response (length={})", response.length());
                return AgentResult.builder().success(false).errorMessage("No JSON in response").build();
            }

            int jsonEnd = response.lastIndexOf("}");
            String jsonStr = (jsonEnd > jsonStart)
                    ? response.substring(jsonStart, jsonEnd + 1)
                    : response.substring(jsonStart); // truncated, no closing brace
            JsonNode json = parseJsonWithRepair(jsonStr);

            if (json == null) {
                log.error("All JSON repair strategies failed for response (length={})", response.length());
                return AgentResult.builder().success(false)
                        .errorMessage("Failed to parse JSON from Claude response").build();
            }

            return buildResultFromJson(json, ticketId);

        } catch (Exception e) {
            log.error("Failed to parse Claude response", e);
            return AgentResult.builder().success(false)
                    .errorMessage("Parse error: " + e.getMessage()).build();
        }
    }

    /**
     * Attempts to parse JSON with multiple repair strategies for handling
     * malformed JSON from auto-continuation stitching.
     */
    JsonNode parseJsonWithRepair(String jsonStr) {
        // Strategy 1: Direct parse
        try {
            return objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("Direct JSON parse failed: {}", e.getMessage());
        }

        // Strategy 2: Close any open JSON constructs (handles truncation)
        try {
            String closed = closeJsonString(jsonStr);
            JsonNode node = objectMapper.readTree(closed);
            if (node.has("files")) {
                log.info("JSON repair succeeded: closure strategy");
                return node;
            }
        } catch (Exception e) {
            log.warn("Closure repair failed: {}", e.getMessage());
        }

        // Strategy 3: Find error position, truncate before it, close
        int errorPos = findJsonErrorPosition(jsonStr);
        if (errorPos > 100) {
            log.info("JSON error at char offset {}, attempting truncation repair", errorPos);
            for (int i = errorPos - 1; i > Math.max(50, errorPos - 2000); i--) {
                char c = jsonStr.charAt(i);
                if (c == '}' || c == ']' || c == '"' || c == ',') {
                    String prefix = jsonStr.substring(0, i + 1);
                    String closed = closeJsonString(prefix);
                    try {
                        JsonNode node = objectMapper.readTree(closed);
                        if (node.has("files")) {
                            log.info("JSON repair succeeded: truncation at {} (error at {}), preserved {}/{} chars",
                                    i, errorPos, i, jsonStr.length());
                            return node;
                        }
                    } catch (Exception ignored) {
                        // Try next position
                    }
                }
            }
        }

        log.error("All JSON repair strategies exhausted");
        return null;
    }

    private AgentResult buildResultFromJson(JsonNode json, String ticketId) {
        List<AgentResult.GeneratedFile> files = new ArrayList<>();
        if (json.has("files")) {
            for (JsonNode fileNode : json.get("files")) {
                try {
                    String path = fileNode.has("path") ? fileNode.get("path").asText() : null;
                    if (path == null) continue;

                    String content;
                    if (fileNode.has("contentBase64")) {
                        String base64Content = fileNode.get("contentBase64").asText();
                        content = new String(java.util.Base64.getDecoder().decode(base64Content),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } else if (fileNode.has("content")) {
                        content = fileNode.get("content").asText();
                    } else {
                        log.warn("No content for file: {}", path);
                        continue;
                    }

                    String operation = fileNode.has("operation")
                            ? fileNode.get("operation").asText().toUpperCase()
                            : "CREATE";

                    files.add(AgentResult.GeneratedFile.builder()
                            .path(path)
                            .content(content)
                            .operation(AgentResult.FileOperation.valueOf(operation))
                            .build());
                } catch (Exception fileEx) {
                    log.warn("Failed to parse file node: {}", fileEx.getMessage());
                }
            }
        }

        return AgentResult.builder()
                .ticketId(ticketId)
                .success(!files.isEmpty() || json.has("commitMessage"))
                .generatedFiles(files)
                .commitMessage(json.has("commitMessage") ? json.get("commitMessage").asText() : null)
                .prTitle(json.has("prTitle") ? json.get("prTitle").asText() : null)
                .prDescription(json.has("prDescription") ? json.get("prDescription").asText() : null)
                .build();
    }

    /**
     * Finds the character offset where JSON parsing fails.
     * Returns -1 if no error is found.
     */
    private int findJsonErrorPosition(String jsonStr) {
        try (com.fasterxml.jackson.core.JsonParser parser =
                     objectMapper.getFactory().createParser(jsonStr)) {
            while (parser.nextToken() != null) {
                // consume all tokens
            }
        } catch (JsonParseException e) {
            return (int) e.getLocation().getCharOffset();
        } catch (Exception e) {
            // other error
        }
        return -1;
    }

    /**
     * Closes any open JSON constructs (strings, arrays, objects) to make
     * truncated JSON parseable. Uses a stack-based approach to track nesting.
     */
    String closeJsonString(String partial) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < partial.length(); i++) {
            char c = partial.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    stack.pop();
                }
                continue;
            }
            switch (c) {
                case '"': inString = true; stack.push('"'); break;
                case '{': stack.push('{'); break;
                case '[': stack.push('['); break;
                case '}':
                    if (!stack.isEmpty() && stack.peek() == '{') stack.pop();
                    break;
                case ']':
                    if (!stack.isEmpty() && stack.peek() == '[') stack.pop();
                    break;
                default: break;
            }
        }

        // Trim trailing commas/whitespace if not inside a string
        String base = partial;
        if (!inString) {
            int end = base.length() - 1;
            while (end >= 0) {
                char c = base.charAt(end);
                if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    end--;
                } else {
                    break;
                }
            }
            base = base.substring(0, end + 1);
        }

        StringBuilder sb = new StringBuilder(base);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            switch (open) {
                case '"': sb.append('"'); break;
                case '{': sb.append('}'); break;
                case '[': sb.append(']'); break;
                default: break;
            }
        }
        return sb.toString();
    }

    private void updateState(String ticketId, String ticketKey, ProcessingStatus status) {
        ticketStateRepository.save(TicketState.forTicket(ticketId, ticketKey, status)
                .withAgentType("claude-opus"));
    }
}
