package com.aidriven.app.ai;

import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.DynamoChatMemoryRepository;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration for the ChatClient, ChatMemory, and Advisors.
 *
 * <p>This replaces the library-only usage of {@code SpringAiClientAdapter}
 * with the full Spring AI ChatClient backed by auto-configured
 * {@code AnthropicChatModel} and DynamoDB-backed conversation memory.
 *
 * <p>The ChatClient is pre-configured with:
 * <ul>
 *   <li>{@link MessageChatMemoryAdvisor} for automatic conversation history management</li>
 *   <li>{@link SimpleLoggerAdvisor} for request/response logging</li>
 *   <li>{@link AgentAdvisor} for token tracking, cost management, and audit logging</li>
 * </ul>
 */
@Configuration
public class SpringAiConfig {

    private static final int DEFAULT_MAX_MESSAGES = 20;

    @Bean
    ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory, AgentAdvisor agentAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .build(),
                        agentAdvisor,
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    @Bean
    ChatMemory chatMemory(DynamoChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(DEFAULT_MAX_MESSAGES)
                .build();
    }

    @Bean
    DynamoChatMemoryRepository dynamoChatMemoryRepository(ConversationRepository conversationRepository) {
        return new DynamoChatMemoryRepository(conversationRepository);
    }
}
