package com.aidriven.tool.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IssueTrackerModuleDescriptorTest {

    @Test
    void should_have_jira_module_id() {
        assertEquals("issue_tracker_jira", IssueTrackerModuleDescriptor.JIRA_MODULE_ID);
    }

    @Test
    void should_have_jira_required_keys() {
        assertTrue(IssueTrackerModuleDescriptor.JIRA_REQUIRED_KEYS.contains("jira_secret_arn"));
        assertTrue(IssueTrackerModuleDescriptor.JIRA_REQUIRED_KEYS.contains("jira_base_url"));
        assertEquals(2, IssueTrackerModuleDescriptor.JIRA_REQUIRED_KEYS.size());
    }

    @Test
    void should_have_default_config() {
        assertNotNull(IssueTrackerModuleDescriptor.JIRA_DEFAULTS);
        assertEquals("3", IssueTrackerModuleDescriptor.JIRA_DEFAULTS.get("api_version"));
    }
}
