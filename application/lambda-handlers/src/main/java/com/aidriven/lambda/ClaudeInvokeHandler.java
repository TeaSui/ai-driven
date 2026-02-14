package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.claude.PromptBuilder;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.model.GenerationMetrics;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import com.aidriven.core.util.JsonRepairService;
import com.aidriven.lambda.factory.ServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Lambda handler for direct Claude API invocation in the linear workflow.
 */
@Slf4j
@RequiredArgsConstructor
public class ClaudeInvokeHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final GenerationMetricsRepository metricsRepository;
    private final ContextStorageService contextStorageService;
    private final ClaudeClient claudeClient;
    private final JsonRepairService jsonRepairService;
    private final int maxContext;
    private final String promptVersion;

    /** No-arg constructor required by AWS Lambda runtime. */
    public ClaudeInvokeHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.metricsRepository = factory.getGenerationMetricsRepository();
        this.contextStorageService = factory.getContextStorageService();
        this.claudeClient = factory.getClaudeClient();
        this.jsonRepairService = new JsonRepairService(factory.getObjectMapper());
        this.maxContext = factory.getAppConfig().getMaxContextForClaude();
        this.promptVersion = factory.getAppConfig().getPromptVersion();
    }

    @Override
    @SuppressWarnings("unchecked")
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {

        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");
        boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));

        LoggingUtils.appendKey("ticketKey", ticketKey);
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());

        log.info("ClaudeInvokeHandler processing ticket: {} (dryRun={})", ticketKey, dryRun);

        try {
            TicketInfo ticket = parseTicketInfo(ticketId, ticketKey, input);
            updateStatus(ticketId, ticketKey, ProcessingStatus.GENERATING);

            String codeContext = loadContextFromS3((String) input.get("codeContextS3Key"));
            String systemPrompt = PromptBuilder.backendAgentSystemPrompt();
            String userMessage = buildUserMessage(ticket, codeContext);

            ClaudeClient activeClient = resolveActiveClient((String) input.get("resolvedModel"));
            log.info("Invoking Claude ({}) with prompt length: {} chars", activeClient.getModel(),
                    userMessage.length());

            String response = activeClient.chat(systemPrompt, userMessage);
            AgentResult result = parseClaudeResponse(response, ticketId);

            recordMetrics(ticketKey, activeClient.getModel(), userMessage.length(), response.length(), result,
                    (List<String>) input.get("labels"));

            return buildOutput(ticketId, ticketKey, dryRun, activeClient.getModel(), result, input);

        } catch (Exception e) {
            log.error("ClaudeInvokeHandler failed for ticket: {}", ticketKey, e);
            updateStatus(ticketId, ticketKey, ProcessingStatus.FAILED);
            throw new RuntimeException("Claude invocation failed", e);
        } finally {
            // Context cleared by Powertools
        }
    }

    @SuppressWarnings("unchecked")
    private TicketInfo parseTicketInfo(String id, String key, Map<String, Object> input) {
        return TicketInfo.builder()
                .ticketId(id)
                .ticketKey(key)
                .summary((String) input.get("summary"))
                .description((String) input.get("description"))
                .labels((List<String>) input.get("labels"))
                .priority((String) input.get("priority"))
                .build();
    }

    private String loadContextFromS3(String s3Key) {
        if (s3Key == null || s3Key.isEmpty()) {
            log.warn("No codeContextS3Key provided, proceeding without code context");
            return "";
        }
        try {
            String context = contextStorageService.getContext(s3Key);
            log.info("Loaded context from S3: {} ({} chars)", s3Key, context.length());
            if (context.length() > maxContext) {
                log.warn("Truncating context ({} chars) to limit ({} chars)", context.length(), maxContext);
                return context.substring(0, maxContext) + "\n\n... [context truncated]";
            }
            return context;
        } catch (Exception e) {
            log.warn("Failed to load context from S3 key {}: {}", s3Key, e.getMessage());
            return "";
        }
    }

    private String buildUserMessage(TicketInfo ticket, String codeContext) {
        StringBuilder msg = new StringBuilder();
        if (!codeContext.isEmpty()) {
            msg.append(codeContext).append("\n\n---\n\n");
        }
        msg.append(PromptBuilder.buildUserMessage(ticket));
        return msg.toString();
    }

    private ClaudeClient resolveActiveClient(String resolvedModel) {
        return resolvedModel != null ? claudeClient.withModel(resolvedModel) : claudeClient;
    }

    private void recordMetrics(String ticketKey, String model, int inputLen, int outputLen, AgentResult result,
            List<String> labels) {
        if (metricsRepository == null)
            return;
        try {
            int fileCount = result.getGeneratedFiles() != null ? result.getGeneratedFiles().size() : 0;
            GenerationMetrics metrics = GenerationMetrics.forGeneration(ticketKey, model, promptVersion, inputLen,
                    outputLen, fileCount, labels);
            metricsRepository.save(metrics);
        } catch (Exception me) {
            log.warn("Failed to save generation metrics for ticket {}: {}", ticketKey, me.getMessage());
        }
    }

    private Map<String, Object> buildOutput(String id, String key, boolean dryRun, String model, AgentResult result,
            Map<String, Object> input) throws Exception {
        Map<String, Object> out = new HashMap<>();
        out.put("ticketId", id);
        out.put("ticketKey", key);
        out.put("success", result.isSuccess());
        out.put("dryRun", dryRun);
        out.put("platform", input.getOrDefault("platform", "BITBUCKET"));
        out.put("repoOwner", input.getOrDefault("repoOwner", ""));
        out.put("repoSlug", input.getOrDefault("repoSlug", ""));
        out.put("files",
                result.getGeneratedFiles() != null ? objectMapper.writeValueAsString(result.getGeneratedFiles())
                        : "[]");
        out.put("commitMessage", Objects.toString(result.getCommitMessage(), "feat: auto-generated changes"));
        out.put("prTitle", Objects.toString(result.getPrTitle(), "Auto-generated PR"));
        out.put("prDescription", Objects.toString(result.getPrDescription(), "Auto-generated by AI-Driven system"));
        out.put("agentType", model);
        return out;
    }

    private AgentResult parseClaudeResponse(String response, String ticketId) {
        try {
            int start = response.indexOf("{");
            int end = response.lastIndexOf("}");
            if (start < 0)
                return AgentResult.builder().success(false).errorMessage("No JSON in response").build();

            String jsonStr = (end > start) ? response.substring(start, end + 1) : response.substring(start);
            JsonNode json = jsonRepairService.parseJsonWithRepair(jsonStr);

            if (json == null)
                return AgentResult.builder().success(false).errorMessage("Failed to parse JSON").build();

            List<AgentResult.GeneratedFile> files = new ArrayList<>();
            if (json.has("files")) {
                for (JsonNode f : json.get("files")) {
                    AgentResult.GeneratedFile gf = parseFileNode(f);
                    if (gf != null)
                        files.add(gf);
                }
            }

            return AgentResult.builder()
                    .ticketId(ticketId)
                    .success(!files.isEmpty() || json.has("commitMessage"))
                    .generatedFiles(files)
                    .commitMessage(json.path("commitMessage").asText(null))
                    .prTitle(json.path("prTitle").asText(null))
                    .prDescription(json.path("prDescription").asText(null))
                    .build();
        } catch (Exception e) {
            return AgentResult.builder().success(false).errorMessage("Parse error: " + e.getMessage()).build();
        }
    }

    private AgentResult.GeneratedFile parseFileNode(JsonNode node) {
        String path = node.path("path").asText(null);
        if (path == null)
            return null;

        String content;
        if (node.has("contentBase64")) {
            content = new String(Base64.getDecoder().decode(node.get("contentBase64").asText()),
                    StandardCharsets.UTF_8);
        } else {
            content = node.path("content").asText(null);
        }
        if (content == null)
            return null;

        String op = node.path("operation").asText("CREATE").toUpperCase();
        return AgentResult.GeneratedFile.builder()
                .path(path)
                .content(content)
                .operation(AgentResult.FileOperation.valueOf(op))
                .build();
    }

    private void updateStatus(String id, String key, ProcessingStatus status) {
        ticketStateRepository.save(TicketState.forTicket(id, key, status).withAgentType("claude-opus"));
    }
}
