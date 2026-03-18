package com.aidriven.app.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Service that uses Spring AI's structured output (entity extraction) for pipeline mode.
 *
 * <p>Replaces manual JSON parsing previously done in {@code ClaudeInvokeHandler} by
 * leveraging the ChatClient's {@code entity()} method, which uses Spring AI's
 * {@code BeanOutputConverter} to map model output directly to Java types.
 *
 * <p>Example usage for code generation pipeline:
 * <pre>{@code
 * record GeneratedCode(List<GeneratedFile> files, String summary) {}
 * GeneratedCode result = structuredOutputService.generate(systemPrompt, ticketDesc, GeneratedCode.class);
 * }</pre>
 *
 * <p>The model is instructed (via Spring AI's output parser) to produce JSON that
 * conforms to the target type's schema. The response is then deserialized automatically.
 */
@Slf4j
@Service
public class StructuredOutputService {

    private final ChatClient chatClient;

    public StructuredOutputService(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    /**
     * Generates a structured response from the AI model, parsed into the given type.
     *
     * <p>Spring AI handles:
     * <ol>
     *   <li>Appending format instructions to the prompt based on the target schema</li>
     *   <li>Parsing the model's JSON response into the target type</li>
     *   <li>Retrying if the model produces malformed JSON (via the ChatClient's retry config)</li>
     * </ol>
     *
     * @param systemPrompt the system prompt providing context and instructions
     * @param userMessage  the user message (e.g., ticket description, code context)
     * @param responseType the target Java class to deserialize the response into
     * @param <T>          the response type
     * @return the structured response parsed from the model's output
     * @throws org.springframework.ai.converter.StructuredOutputException if parsing fails
     */
    public <T> T generate(String systemPrompt, String userMessage, Class<T> responseType) {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        Objects.requireNonNull(responseType, "responseType must not be null");

        log.info("Generating structured output: type={}, userMessageLength={}",
                responseType.getSimpleName(), userMessage.length());

        T result = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .entity(responseType);

        log.info("Structured output generated successfully: type={}", responseType.getSimpleName());
        return result;
    }
}
