package com.aidriven.app.ai;

import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.audit.AuditService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Custom Spring AI advisor for cross-cutting agent concerns.
 *
 * <p>Wraps every ChatClient call to provide:
 * <ul>
 *   <li><b>Token counting and cost tracking:</b> Records token consumption per ticket
 *       via the existing {@link CostTracker} (DynamoDB-backed, atomic updates).</li>
 *   <li><b>Audit logging:</b> Persists prompt/response pairs to S3 via the existing
 *       {@link AuditService} for compliance trails.</li>
 *   <li><b>Latency measurement:</b> Logs wall-clock duration of each AI call.</li>
 * </ul>
 *
 * <p>This advisor runs at {@link Ordered#LOWEST_PRECEDENCE} to execute after
 * memory and other advisors have enriched the request, ensuring that the
 * audited prompt reflects the full context sent to the model.
 *
 * <p>The advisor extracts the {@code ticketKey} from the advisor context parameters.
 * If no ticket key is provided, cost tracking is skipped but audit logging still occurs
 * with a placeholder identifier.
 */
@Slf4j
@Component
public class AgentAdvisor implements CallAdvisor {

    /** Advisor context parameter key for the ticket being processed. */
    public static final String TICKET_KEY_PARAM = "agent_ticket_key";

    private static final String ADVISOR_NAME = "AgentAdvisor";
    private static final String UNKNOWN_TICKET = "unknown";

    private final CostTracker costTracker;
    private final AuditService auditService;

    public AgentAdvisor(CostTracker costTracker, AuditService auditService) {
        this.costTracker = Objects.requireNonNull(costTracker, "costTracker must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String ticketKey = resolveTicketKey(request);
        long startTimeMs = System.currentTimeMillis();

        log.info("AgentAdvisor: starting call for ticket={}", ticketKey);

        ChatClientResponse response = chain.nextCall(request);

        long durationMs = System.currentTimeMillis() - startTimeMs;

        trackTokenUsage(ticketKey, response, durationMs);
        recordAuditTrail(ticketKey, request, response, durationMs);

        return response;
    }

    private String resolveTicketKey(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        if (context != null) {
            Object ticketKeyObj = context.get(TICKET_KEY_PARAM);
            if (ticketKeyObj instanceof String ticketKey && !ticketKey.isBlank()) {
                return ticketKey;
            }
        }
        return UNKNOWN_TICKET;
    }

    private void trackTokenUsage(String ticketKey, ChatClientResponse clientResponse, long durationMs) {
        try {
            ChatResponse chatResponse = clientResponse.chatResponse();
            if (chatResponse == null || chatResponse.getMetadata() == null) {
                return;
            }

            var usage = chatResponse.getMetadata().getUsage();
            if (usage == null) {
                return;
            }

            long totalTokens = usage.getTotalTokens();
            if (totalTokens > 0 && !UNKNOWN_TICKET.equals(ticketKey)) {
                costTracker.addTokens(ticketKey, (int) totalTokens);
            }

            log.info("AgentAdvisor: ticket={}, inputTokens={}, outputTokens={}, totalTokens={}, durationMs={}",
                    ticketKey,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    totalTokens,
                    durationMs);
        } catch (Exception e) {
            log.warn("AgentAdvisor: failed to track token usage for ticket={}: {}",
                    ticketKey, e.getMessage());
        }
    }

    private void recordAuditTrail(String ticketKey, ChatClientRequest request,
                                  ChatClientResponse clientResponse, long durationMs) {
        try {
            String systemPrompt = extractSystemPrompt(request);
            String userPrompt = extractUserText(request);
            String modelResponse = extractModelResponse(clientResponse);

            Map<String, Object> metadata = buildAuditMetadata(clientResponse, durationMs);

            auditService.recordInvocation(ticketKey, systemPrompt, userPrompt, modelResponse, metadata);
        } catch (Exception e) {
            log.warn("AgentAdvisor: failed to record audit trail for ticket={}: {}",
                    ticketKey, e.getMessage());
        }
    }

    private String extractSystemPrompt(ChatClientRequest request) {
        var prompt = request.prompt();
        if (prompt != null && prompt.getInstructions() != null) {
            return prompt.getInstructions().stream()
                    .filter(msg -> msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM)
                    .map(msg -> msg.getText() != null ? msg.getText() : "")
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private String extractUserText(ChatClientRequest request) {
        var prompt = request.prompt();
        if (prompt != null && prompt.getInstructions() != null) {
            return prompt.getInstructions().stream()
                    .filter(msg -> msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                    .map(msg -> msg.getText() != null ? msg.getText() : "")
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private String extractModelResponse(ChatClientResponse clientResponse) {
        ChatResponse chatResponse = clientResponse.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return "";
        }
        var output = chatResponse.getResult().getOutput();
        return output != null && output.getText() != null ? output.getText() : "";
    }

    private Map<String, Object> buildAuditMetadata(ChatClientResponse clientResponse, long durationMs) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("durationMs", durationMs);
        metadata.put("advisor", ADVISOR_NAME);

        ChatResponse chatResponse = clientResponse.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                metadata.put("inputTokens", usage.getPromptTokens());
                metadata.put("outputTokens", usage.getCompletionTokens());
                metadata.put("totalTokens", usage.getTotalTokens());
            }
            String model = chatResponse.getMetadata().getModel();
            if (model != null) {
                metadata.put("model", model);
            }
        }
        return metadata;
    }
}
