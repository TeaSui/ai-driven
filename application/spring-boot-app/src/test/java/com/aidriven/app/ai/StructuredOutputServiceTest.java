package com.aidriven.app.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@ExtendWith(MockitoExtension.class)
class StructuredOutputServiceTest {

    @Mock
    private ChatClient chatClient;

    private StructuredOutputService service;

    @BeforeEach
    void setUp() {
        service = new StructuredOutputService(chatClient);
    }

    @Test
    void should_throw_when_chat_client_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StructuredOutputService(null))
                .withMessage("chatClient must not be null");
    }

    @Test
    void should_throw_when_system_prompt_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.generate(null, "message", String.class))
                .withMessage("systemPrompt must not be null");
    }

    @Test
    void should_throw_when_user_message_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.generate("system", null, String.class))
                .withMessage("userMessage must not be null");
    }

    @Test
    void should_throw_when_response_type_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.generate("system", "message", null))
                .withMessage("responseType must not be null");
    }
}
