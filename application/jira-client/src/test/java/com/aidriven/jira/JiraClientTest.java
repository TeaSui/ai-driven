package com.aidriven.jira;

import com.aidriven.core.tracker.IssueTrackerClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraClientTest {

    private final JiraClient client = new JiraClient("https://test.atlassian.net", "test@test.com", "token");

    @Test
    void should_implement_issueTrackerClient() {
        assertTrue(client instanceof IssueTrackerClient,
                "JiraClient should implement IssueTrackerClient");
    }
}
