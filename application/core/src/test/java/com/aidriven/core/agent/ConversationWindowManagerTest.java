package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ConversationWindowManager.
 * Verifies token budget enforcement and conversation pruning.
 */
class ConversationWindowManagerTest {

    private InMemoryConversationRepository repository;
    private ConversationWindowManager windowManager;

    private static final int TOKEN_BUDGET = 1000;
    private static final int RECENT_MESSAGES_TO_KEEP = 2;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConversationRepository();
        windowManager = new ConversationWindowManager(repository, TOKEN_BUDGET, RECENT_MESSAGES_TO_KEEP);
    }

    // --- within budget ---

    @Test
    void returns_all_messages_when_within_budget() {
        saveMessage("ONC-100", 1, "user", 100);
        saveMessage("ONC-100", 2, "assistant", 200);
        saveMessage("ONC-100", 3, "user", 100);

        List<Map<String, Object>> messages = windowManager.buildMessages("ONC-100");

        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("user", messages.get(2).get("role"));
    }

    @Test
    void returns_empty_list_for_no_conversation() {
        List<Map<String, Object>> messages = windowManager.buildMessages("UNKNOWN-999");
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    // --- over budget: pruning ---

    @Test
    void prunes_oldest_messages_when_over_budget() {
        // Budget = 1000, keepRecent = 2
        saveMessage("ONC-100", 1, "user", 400); // old → pruned (no room after msg2)
        saveMessage("ONC-100", 2, "assistant", 400); // old → included (fits in remaining 400)
        saveMessage("ONC-100", 3, "user", 300); // kept (recent)
        saveMessage("ONC-100", 4, "assistant", 300); // kept (recent)

        // Total = 1400 > 1000. Recent = 600. Remaining = 400. Msg2 (400) fits. Msg1
        // won't.
        List<Map<String, Object>> messages = windowManager.buildMessages("ONC-100");

        // msg2 + msg3 + msg4 = 1000 (exactly budget)
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.get(0).get("role")); // msg2
        assertEquals("user", messages.get(1).get("role")); // msg3
        assertEquals("assistant", messages.get(2).get("role")); // msg4
    }

    @Test
    void keeps_all_recent_messages_even_if_they_exceed_budget() {
        // Edge case: even the recent messages alone exceed budget
        saveMessage("ONC-100", 1, "user", 600);
        saveMessage("ONC-100", 2, "assistant", 600);

        // Total = 1200 > 1000, but keepRecent=2 means we keep both
        List<Map<String, Object>> messages = windowManager.buildMessages("ONC-100");
        assertEquals(2, messages.size());
    }

    @Test
    void prunes_progressively_until_under_budget() {
        // 5 messages, each 300 tokens = 1500 total > 1000 budget
        saveMessage("ONC-100", 1, "user", 300);
        saveMessage("ONC-100", 2, "assistant", 300);
        saveMessage("ONC-100", 3, "user", 300);
        saveMessage("ONC-100", 4, "assistant", 300);
        saveMessage("ONC-100", 5, "user", 300);

        List<Map<String, Object>> messages = windowManager.buildMessages("ONC-100");

        // Must include at least 2 recent + as many older as fit
        // Recent (4,5) = 600 tokens. Budget remaining = 400. Can fit msg 3 (300).
        // Total = 900 <= 1000. So we keep messages 3, 4, 5.
        assertEquals(3, messages.size());
    }

    // --- appending new message ---

    @Test
    void appendAndBuild_saves_message_then_builds_window() {
        saveMessage("ONC-100", 1, "user", 100);
        saveMessage("ONC-100", 2, "assistant", 200);

        ConversationMessage newMsg = buildMessage("ONC-100", 3, "user", 100);
        List<Map<String, Object>> messages = windowManager.appendAndBuild("ONC-100", newMsg);

        assertEquals(3, messages.size());
        // Also verify it was persisted
        assertEquals(3, repository.getConversation("ONC-100").size());
    }

    @Test
    void appendAndBuild_triggers_pruning_if_over_budget() {
        // Fill history close to budget
        saveMessage("ONC-100", 1, "user", 400);
        saveMessage("ONC-100", 2, "assistant", 400);

        // Append one more that pushes over budget
        ConversationMessage newMsg = buildMessage("ONC-100", 3, "user", 400);
        List<Map<String, Object>> messages = windowManager.appendAndBuild("ONC-100", newMsg);

        // Total stored = 1200 > 1000. Recent 2 = msgs 2,3 = 800.
        // Budget remaining = 200. Can't fit msg 1 (400). So keep 2 messages.
        assertEquals(2, messages.size());
    }

    // --- message format ---

    @Test
    void messages_contain_correct_role_and_content_format() {
        String content = "[{\"type\":\"text\",\"text\":\"hello world\"}]";
        ConversationMessage msg = ConversationMessage.builder()
                .pk(ConversationMessage.createPk("ONC-100"))
                .sk(ConversationMessage.createSk(Instant.parse("2026-02-15T10:00:00Z"), 1))
                .role("user")
                .author("tea.nguyen")
                .contentJson(content)
                .timestamp(Instant.parse("2026-02-15T10:00:00Z"))
                .tokenCount(50)
                .build();
        repository.save(msg);

        List<Map<String, Object>> messages = windowManager.buildMessages("ONC-100");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        // Content should be parsed from JSON
        assertNotNull(messages.get(0).get("content"));
    }

    // --- helpers ---

    private void saveMessage(String ticketKey, int seq, String role, int tokens) {
        repository.save(buildMessage(ticketKey, seq, role, tokens));
    }

    private ConversationMessage buildMessage(String ticketKey, int seq, String role, int tokens) {
        Instant timestamp = Instant.parse("2026-02-15T10:00:00Z").plusSeconds(seq * 60L);
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(ticketKey))
                .sk(ConversationMessage.createSk(timestamp, seq))
                .role(role)
                .author(role.equals("user") ? "tea.nguyen" : "ai-agent")
                .contentJson("[{\"type\":\"text\",\"text\":\"message " + seq + "\"}]")
                .timestamp(timestamp)
                .tokenCount(tokens)
                .build();
    }
}
