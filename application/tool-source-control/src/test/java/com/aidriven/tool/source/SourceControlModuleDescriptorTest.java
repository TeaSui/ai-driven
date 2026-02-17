package com.aidriven.tool.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceControlModuleDescriptorTest {

    @Test
    void should_have_bitbucket_module_id() {
        assertEquals("source_control_bitbucket", SourceControlModuleDescriptor.BITBUCKET_MODULE_ID);
    }

    @Test
    void should_have_github_module_id() {
        assertEquals("source_control_github", SourceControlModuleDescriptor.GITHUB_MODULE_ID);
    }

    @Test
    void should_have_bitbucket_required_keys() {
        assertTrue(SourceControlModuleDescriptor.BITBUCKET_REQUIRED_KEYS.contains("bitbucket_secret_arn"));
        assertTrue(SourceControlModuleDescriptor.BITBUCKET_REQUIRED_KEYS.contains("bitbucket_workspace"));
        assertTrue(SourceControlModuleDescriptor.BITBUCKET_REQUIRED_KEYS.contains("bitbucket_repo_slug"));
        assertEquals(3, SourceControlModuleDescriptor.BITBUCKET_REQUIRED_KEYS.size());
    }

    @Test
    void should_have_github_required_keys() {
        assertTrue(SourceControlModuleDescriptor.GITHUB_REQUIRED_KEYS.contains("github_secret_arn"));
        assertTrue(SourceControlModuleDescriptor.GITHUB_REQUIRED_KEYS.contains("github_owner"));
        assertTrue(SourceControlModuleDescriptor.GITHUB_REQUIRED_KEYS.contains("github_repo"));
        assertEquals(3, SourceControlModuleDescriptor.GITHUB_REQUIRED_KEYS.size());
    }

    @Test
    void should_have_default_configs() {
        assertNotNull(SourceControlModuleDescriptor.BITBUCKET_DEFAULTS);
        assertNotNull(SourceControlModuleDescriptor.GITHUB_DEFAULTS);
        assertEquals("2.0", SourceControlModuleDescriptor.BITBUCKET_DEFAULTS.get("api_version"));
        assertEquals("v3", SourceControlModuleDescriptor.GITHUB_DEFAULTS.get("api_version"));
    }
}
