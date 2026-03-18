package com.aidriven.app.ai;

import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.DynamoChatMemoryRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SpringAiConfigTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatModel chatModel;

    @Mock
    private AgentAdvisor agentAdvisor;

    private final SpringAiConfig config = new SpringAiConfig();

    @Test
    void should_create_dynamo_chat_memory_repository_when_conversation_repository_provided() {
        DynamoChatMemoryRepository repository = config.dynamoChatMemoryRepository(conversationRepository);

        assertThat(repository).isNotNull();
    }

    @Test
    void should_create_chat_memory_when_repository_provided() {
        DynamoChatMemoryRepository repository = config.dynamoChatMemoryRepository(conversationRepository);

        ChatMemory chatMemory = config.chatMemory(repository);

        assertThat(chatMemory).isNotNull();
    }

    @Test
    void should_create_chat_client_when_all_dependencies_provided() {
        DynamoChatMemoryRepository repository = config.dynamoChatMemoryRepository(conversationRepository);
        ChatMemory chatMemory = config.chatMemory(repository);

        ChatClient chatClient = config.chatClient(chatModel, chatMemory, agentAdvisor);

        assertThat(chatClient).isNotNull();
    }
}
