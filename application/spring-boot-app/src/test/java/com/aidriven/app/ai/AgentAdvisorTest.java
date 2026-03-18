package com.aidriven.app.ai;

import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.audit.AuditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAdvisorTest {

    @Mock
    private CostTracker costTracker;

    @Mock
    private AuditService auditService;

    @Mock
    private CallAdvisorChain chain;

    private AgentAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new AgentAdvisor(costTracker, auditService);
    }

    @Test
    void should_throw_when_cost_tracker_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentAdvisor(null, auditService))
                .withMessage("costTracker must not be null");
    }

    @Test
    void should_throw_when_audit_service_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentAdvisor(costTracker, null))
                .withMessage("auditService must not be null");
    }

    @Test
    void should_return_correct_name() {
        assertThat(advisor.getName()).isEqualTo("AgentAdvisor");
    }

    @Test
    void should_have_lowest_precedence_order() {
        assertThat(advisor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void should_track_tokens_when_ticket_key_provided() {
        ChatClientRequest request = buildRequest(Map.of(AgentAdvisor.TICKET_KEY_PARAM, "ONC-100"));
        ChatClientResponse response = buildResponse(100, 50);
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        verify(costTracker).addTokens(eq("ONC-100"), eq(150));
    }

    @Test
    void should_not_track_tokens_when_ticket_key_missing() {
        ChatClientRequest request = buildRequest(Map.of());
        ChatClientResponse response = buildResponse(100, 50);
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        verify(costTracker, never()).addTokens(anyString(), anyInt());
    }

    @Test
    void should_record_audit_trail() {
        ChatClientRequest request = buildRequest(Map.of(AgentAdvisor.TICKET_KEY_PARAM, "ONC-200"));
        ChatClientResponse response = buildResponse(80, 40);
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        verify(auditService).recordInvocation(
                eq("ONC-200"),
                anyString(),
                anyString(),
                anyString(),
                any(Map.class));
    }

    @Test
    void should_pass_through_chain_and_return_response() {
        ChatClientRequest request = buildRequest(Map.of());
        ChatClientResponse expectedResponse = buildResponse(10, 5);
        when(chain.nextCall(any())).thenReturn(expectedResponse);

        ChatClientResponse actualResponse = advisor.adviseCall(request, chain);

        assertThat(actualResponse).isSameAs(expectedResponse);
        verify(chain).nextCall(request);
    }

    @Test
    void should_handle_null_usage_gracefully() {
        ChatClientRequest request = buildRequest(Map.of(AgentAdvisor.TICKET_KEY_PARAM, "ONC-300"));
        ChatClientResponse response = buildResponseWithNullUsage();
        when(chain.nextCall(any())).thenReturn(response);

        // Should not throw
        advisor.adviseCall(request, chain);

        verify(costTracker, never()).addTokens(anyString(), anyInt());
    }

    private ChatClientRequest buildRequest(Map<String, Object> context) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("Test system prompt"),
                new UserMessage("Test user message")));

        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(context)
                .build();
    }

    private ChatClientResponse buildResponse(int promptTokens, int completionTokens) {
        AssistantMessage assistantMessage = new AssistantMessage("Test response");
        Generation generation = new Generation(assistantMessage);

        DefaultUsage usage = new DefaultUsage(promptTokens, completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(usage)
                .build();

        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();
    }

    private ChatClientResponse buildResponseWithNullUsage() {
        AssistantMessage assistantMessage = new AssistantMessage("Test response");
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();
    }
}
