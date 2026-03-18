package com.aidriven.app.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingAgentServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    private StreamingAgentService service;

    @BeforeEach
    void setUp() {
        service = new StreamingAgentService(chatClient);
    }

    @Test
    void should_throw_when_chat_client_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StreamingAgentService(null))
                .withMessage("chatClient must not be null");
    }

    @Test
    void should_throw_when_system_prompt_is_null_for_stream() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.streamResponse(null, "message", "conv-id"))
                .withMessage("systemPrompt must not be null");
    }

    @Test
    void should_throw_when_user_message_is_null_for_stream() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.streamResponse("system", null, "conv-id"))
                .withMessage("userMessage must not be null");
    }

    @Test
    void should_throw_when_conversation_id_is_null_for_stream() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.streamResponse("system", "message", null))
                .withMessage("conversationId must not be null");
    }

    @Test
    void should_throw_when_system_prompt_is_null_for_call() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.callWithTools(null, "message", List.of()))
                .withMessage("systemPrompt must not be null");
    }

    @Test
    void should_throw_when_user_message_is_null_for_call() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.callWithTools("system", null, List.of()))
                .withMessage("userMessage must not be null");
    }

    @Test
    void should_throw_when_tools_is_null_for_call() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.callWithTools("system", "message", null))
                .withMessage("tools must not be null");
    }
}
