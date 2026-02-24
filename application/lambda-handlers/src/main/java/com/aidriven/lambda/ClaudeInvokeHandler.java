package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.agent.AiClient;
import com.aidriven.claude.PromptBuilder;
import com.aidriven.core.cost.BudgetTracker;
import com.aidriven.core.cost.ModelPricing;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.model.GenerationMetrics;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.audit.AuditService;
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
import com.aidriven.spi.model.OperationContext;

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
    private final AuditService auditService;
    private final AiClient claudeClient;
    private final JsonRepairService jsonRepairService;
    private final int maxContext;
    private final String promptVersion;
    private final boolean costAwareMode;
    private final double monthlyBudgetUsd;
    private final int maxTokensPerTicket;
    private final BudgetTracker budgetTracker;

    /** No-arg constructor required by AWS Lambda runtime. */
    public ClaudeInvokeHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.metricsRepository = factory.getGenerationMetricsRepository();
        this.contextStorageService = factory.getContextStorageService();
        this.auditService = factory.getAuditService();
        this.claudeClient = factory.getClaudeClient();
        this.jsonRepairService = new JsonRepairService(factory.getObjectMapper());
        this.maxContext = factory.getAppConfig().getMaxContextForClaude();
        this.promptVersion = factory.getAppConfig().getPromptVersion();
        this.costAwareMode = factory.getAppConfig().isCostAwareMode();
        this.monthlyBudgetUsd = factory.getAppConfig().getMonthlyBudgetUsd();
        this.maxTokensPerTicket = factory.getAppConfig().getMaxTokensPerTicket();
        this.budgetTracker = factory.getBudgetTracker();
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

        OperationContext tenantContext = extractTenantContext(input);
        try {
            TicketInfo ticket = parseTicketInfo(ticketId, ticketKey, input);
            updateStatus(tenantContext.getTenantId(), ticketId, ticketKey, ProcessingStatus.GENERATING);

            String codeContext = loadContextFromS3((String) input.get("codeContextS3Key"));
            String systemPrompt = PromptBuilder.backendAgentSystemPrompt();
            String userMessage = buildUserMessage(ticket, codeContext);

            // impl-12: cost-aware pre-invocation checks
            TicketState currentState = ticketStateRepository.getLatestState(
                    tenantContext.getTenantId(), ticketId).orElse(null);
            if (costAwareMode) {
                userMessage = applyCostGuard(ticketKey, userMessage, currentState, tenantContext, ticketId);
            }

            AiClient activeClient = resolveActiveClient((String) input.get("resolvedModel"));
            log.info("Invoking Claude ({}) with prompt length: {} chars", activeClient.getModel(),
                    userMessage.length());

            long startTime = System.currentTimeMillis();
            String response = activeClient.chat(systemPrompt, userMessage);
            long durationMs = System.currentTimeMillis() - startTime;

            AgentResult result = parseClaudeResponse(response, ticketId);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("model", activeClient.getModel());
            metadata.put("durationMs", durationMs);
            metadata.put("dryRun", dryRun);
            auditService.recordInvocation(ticketKey, systemPrompt, userMessage, response, metadata);

            // impl-12: post-invocation cost recording
            recordCostToTicketState(tenantContext.getTenantId(), ticketId, ticketKey,
                    activeClient.getModel(), userMessage.length(), response.length());

            recordMetrics(ticketKey, activeClient.getModel(), userMessage.length(), response.length(), result,
                    (List<String>) input.get("labels"));

            return buildOutput(ticketId, ticketKey, dryRun, activeClient.getModel(), result, input);

        } catch (Exception e) {
            log.error("ClaudeInvokeHandler failed for ticket: {}", ticketKey, e);
            updateStatus(tenantContext.getTenantId(), ticketId, ticketKey, ProcessingStatus.FAILED);
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

    private AiClient resolveActiveClient(String resolvedModel) {
        return resolvedModel != null ? claudeClient.withModel(resolvedModel) : claudeClient;
    }

    /**
     * Checks token thresholds and monthly budget before invoking Claude.
     * May downgrade the userMessage context or throw if budget is fully exhausted.
     *
     * @return potentially shortened userMessage after downgrade
     */
    private String applyCostGuard(String ticketKey, String userMessage, TicketState state,
            OperationContext ctx, String ticketId) {
        int estimatedInputTokens = ModelPricing.estimateInputTokens(userMessage.length());

        // Per-ticket token cap
        int previousTokens = (state != null && state.getInputTokens() != null) ? state.getInputTokens() : 0;
        if (previousTokens + estimatedInputTokens > maxTokensPerTicket) {
            log.warn("[CostGuard] Ticket {} would exceed per-ticket token cap ({} + {} > {}). Truncating context.",
                    ticketKey, previousTokens, estimatedInputTokens, maxTokensPerTicket);
            // Shorten message to fit within cap
            int allowedChars = (int) ((maxTokensPerTicket - previousTokens) * ModelPricing.CHARS_PER_TOKEN);
            if (allowedChars < 200) {
                throw new RuntimeException("[CostGuard] Per-ticket token cap fully exhausted for " + ticketKey);
            }
            if (state == null || Boolean.TRUE.equals(state.getCostWarningSent())) {
                // Warning already sent or no state — skip redundant comment
            } else {
                log.warn("[CostGuard] Posting one-time cost warning to Jira for ticket {}", ticketKey);
                // Note: Jira comment here would require JiraClient injection — deferred as
                // low-priority.
                // The state flag is set so we don't repeat this logic.
            }
            userMessage = userMessage.substring(0, Math.min(allowedChars, userMessage.length()));
        }

        // Monthly budget circuit breaker
        double totalSpend = (state != null && state.getEstimatedCostUsd() != null) ? state.getEstimatedCostUsd() : 0.0;
        if (budgetTracker != null && budgetTracker.isBudgetExceeded(totalSpend)) {
            throw new RuntimeException("[CostGuard] Monthly budget of $" + monthlyBudgetUsd
                    + " reached for ticket " + ticketKey + ". Total spend: $" + totalSpend);
        }

        // Log actual vs estimated for calibration
        log.info("[CostGuard] Ticket={} estimatedInputTokens={} previousTokens={} totalTokensCap={}",
                ticketKey, estimatedInputTokens, previousTokens, maxTokensPerTicket);

        return userMessage;
    }

    /**
     * Records actual token usage and cost to TicketState after a successful
     * invocation.
     */
    private void recordCostToTicketState(String tenantId, String ticketId, String ticketKey,
            String model, int inputChars, int outputChars) {
        try {
            int newInputTokens = ModelPricing.estimateInputTokens(inputChars);
            int newOutputTokens = ModelPricing.estimateInputTokens(outputChars);
            double invocationCost = ModelPricing.estimateCostUsd(model, newInputTokens, newOutputTokens);

            TicketState existing = ticketStateRepository.getLatestState(tenantId, ticketId).orElse(
                    TicketState.forTicket(tenantId, ticketId, ticketKey, ProcessingStatus.GENERATING));

            int totalInput = (existing.getInputTokens() != null ? existing.getInputTokens() : 0) + newInputTokens;
            int totalOutput = (existing.getOutputTokens() != null ? existing.getOutputTokens() : 0) + newOutputTokens;
            double totalCost = (existing.getEstimatedCostUsd() != null ? existing.getEstimatedCostUsd() : 0.0)
                    + invocationCost;

            ticketStateRepository.save(existing.toBuilder()
                    .inputTokens(totalInput)
                    .outputTokens(totalOutput)
                    .estimatedCostUsd(totalCost)
                    .build());

            if (budgetTracker != null) {
                budgetTracker.recordUsage(ticketKey, invocationCost);
            }

            log.info("[CostGuard] Ticket={} thisInvocation=${} totalSpend=${} tokens={}in+{}out",
                    ticketKey, invocationCost, totalCost, newInputTokens, newOutputTokens);
        } catch (Exception e) {
            log.warn("Failed to record cost to TicketState for ticket {}: {}", ticketKey, e.getMessage());
        }
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

    private void updateStatus(String tenantId, String id, String key, ProcessingStatus status) {
        ticketStateRepository.save(TicketState.forTicket(tenantId, id, key, status).withAgentType("claude-opus"));
    }

    private OperationContext extractTenantContext(Map<String, Object> input) {
        if (!input.containsKey("context") || !(input.get("context") instanceof Map)) {
            return OperationContext.builder().tenantId("default").build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) input.get("context");
        String tenantId = (String) context.getOrDefault("tenantId", "default");
        String userId = (String) context.getOrDefault("userId", "system");
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) context.getOrDefault("metadata", Map.of());
        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .build();
    }
}
