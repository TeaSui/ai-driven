package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ConversationRepository.
 * Tests use an in-memory implementation to avoid DynamoDB dependency.
 */
class ConversationRepositoryTest {

    private ConversationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConversationRepository();
    }

    // --- save & load ---

    @Test
    void save_and_load_single_message() {
        ConversationMessage msg = ConversationMessage.builder()
                .pk(ConversationMessage.createPk("ONC-100"))
                .sk(ConversationMessage.createSk(Instant.parse("2026-02-15T10:00:00Z"), 1))
                .role("user")
                .author("tea.nguyen")
                .contentJson("[{\"type\":\"text\",\"text\":\"@ai investigate the bug\"}]")
                .commentId("jira-123")
                .timestamp(Instant.parse("2026-02-15T10:00:00Z"))
                .tokenCount(50)
                .ttl(ConversationMessage.defaultTtl())
                .build();

        repository.save(msg);

        List<ConversationMessage> history = repository.getConversation("ONC-100");
        assertEquals(1, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("tea.nguyen", history.get(0).getAuthor());
        assertEquals("jira-123", history.get(0).getCommentId());
    }

    @Test
    void load_returns_messages_sorted_by_sort_key() {
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-15T10:01:00Z");
        Instant t3 = Instant.parse("2026-02-15T10:02:00Z");

        // Save out of order
        saveMessage("ONC-100", t2, 1, "assistant", "ai-agent", 200);
        saveMessage("ONC-100", t1, 1, "user", "tea.nguyen", 50);
        saveMessage("ONC-100", t3, 1, "user", "tea.nguyen", 60);

        List<ConversationMessage> history = repository.getConversation("ONC-100");
        assertEquals(3, history.size());
        assertEquals("user", history.get(0).getRole()); // t1 first
        assertEquals("assistant", history.get(1).getRole()); // t2 second
        assertEquals("user", history.get(2).getRole()); // t3 third
    }

    @Test
    void load_returns_empty_for_unknown_ticket() {
        List<ConversationMessage> history = repository.getConversation("UNKNOWN-999");
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    // --- ticket isolation ---

    @Test
    void conversations_are_isolated_per_ticket() {
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");

        saveMessage("ONC-100", t1, 1, "user", "alice", 50);
        saveMessage("ONC-200", t1, 1, "user", "bob", 60);

        assertEquals(1, repository.getConversation("ONC-100").size());
        assertEquals(1, repository.getConversation("ONC-200").size());
        assertEquals("alice", repository.getConversation("ONC-100").get(0).getAuthor());
        assertEquals("bob", repository.getConversation("ONC-200").get(0).getAuthor());
    }

    // --- total tokens ---

    @Test
    void getTotalTokens_sums_all_message_tokens() {
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-15T10:01:00Z");

        saveMessage("ONC-100", t1, 1, "user", "tea.nguyen", 100);
        saveMessage("ONC-100", t2, 1, "assistant", "ai-agent", 500);

        int total = repository.getTotalTokens("ONC-100");
        assertEquals(600, total);
    }

    @Test
    void getTotalTokens_returns_zero_for_empty_conversation() {
        assertEquals(0, repository.getTotalTokens("UNKNOWN-999"));
    }

    // --- delete ---

    @Test
    void deleteConversation_removes_all_messages() {
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-15T10:01:00Z");

        saveMessage("ONC-100", t1, 1, "user", "tea.nguyen", 50);
        saveMessage("ONC-100", t2, 1, "assistant", "ai-agent", 200);

        repository.deleteConversation("ONC-100");

        assertTrue(repository.getConversation("ONC-100").isEmpty());
    }

    @Test
    void deleteConversation_does_not_affect_other_tickets() {
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");

        saveMessage("ONC-100", t1, 1, "user", "alice", 50);
        saveMessage("ONC-200", t1, 1, "user", "bob", 60);

        repository.deleteConversation("ONC-100");

        assertTrue(repository.getConversation("ONC-100").isEmpty());
        assertEquals(1, repository.getConversation("ONC-200").size());
    }

    // --- sequence ordering within same timestamp ---

    @Test
    void messages_with_same_timestamp_ordered_by_sequence() {
        Instant t = Instant.parse("2026-02-15T10:00:00Z");

        // Multiple messages at same timestamp, different sequences
        saveMessage("ONC-100", t, 1, "user", "tea.nguyen", 50);
        saveMessage("ONC-100", t, 2, "assistant", "ai-agent", 200);
        saveMessage("ONC-100", t, 3, "user", "tea.nguyen", 60);

        List<ConversationMessage> history = repository.getConversation("ONC-100");
        assertEquals(3, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals("user", history.get(2).getRole());
    }

    // --- helper ---

    private void saveMessage(String ticketKey, Instant timestamp, int seq,
            String role, String author, int tokens) {
        repository.save(ConversationMessage.builder()
                .pk(ConversationMessage.createPk(ticketKey))
                .sk(ConversationMessage.createSk(timestamp, seq))
                .role(role)
                .author(author)
                .contentJson("[{\"type\":\"text\",\"text\":\"test\"}]")
                .timestamp(timestamp)
                .tokenCount(tokens)
                .ttl(ConversationMessage.defaultTtl())
                .build());
    }
}
