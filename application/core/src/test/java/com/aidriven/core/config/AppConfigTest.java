package com.aidriven.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

        @Test
        void should_return_singleton_instance() {
                AppConfig first = AppConfig.getInstance();
                AppConfig second = AppConfig.getInstance();

                assertSame(first, second);
        }

        @Test
        void should_throw_when_required_env_vars_missing() {
                // In test environments, no env vars are set so all required fields are null
                AppConfig config = AppConfig.getInstance();

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                config::validate);

                String message = exception.getMessage();
                assertTrue(message.contains("Missing required environment variables"),
                                "Should mention missing environment variables");
                assertTrue(message.contains("DYNAMODB_TABLE_NAME"),
                                "Should list DYNAMODB_TABLE_NAME as missing");
                assertTrue(message.contains("CLAUDE_SECRET_ARN"),
                                "Should list CLAUDE_SECRET_ARN as missing");
                assertTrue(message.contains("BITBUCKET_SECRET_ARN"),
                                "Should list BITBUCKET_SECRET_ARN as missing");
                assertTrue(message.contains("JIRA_SECRET_ARN"),
                                "Should list JIRA_SECRET_ARN as missing");
                assertTrue(message.contains("CODE_CONTEXT_BUCKET"),
                                "Should list CODE_CONTEXT_BUCKET as missing");
        }

        @Test
        void should_return_null_for_required_fields_when_env_not_set() {
                AppConfig config = AppConfig.getInstance();

                assertNull(config.getDynamoDbTableName(),
                                "Should be null when env var not set");
                assertNull(config.getClaudeSecretArn(),
                                "Should be null when env var not set");
                assertNull(config.getBitbucketSecretArn(),
                                "Should be null when env var not set");
                assertNull(config.getJiraSecretArn(),
                                "Should be null when env var not set");
                assertNull(config.getCodeContextBucket(),
                                "Should be null when env var not set");
        }

        @Test
        void should_return_defaults_for_optional_env_vars() {
                AppConfig config = AppConfig.getInstance();

                assertEquals("claude-opus-4-6", config.getClaudeModel());
                assertEquals(32768, config.getClaudeMaxTokens());
                assertEquals(0.2, config.getClaudeTemperature(), 0.001);
                assertEquals("v1", config.getPromptVersion());
                assertEquals("ai/", config.getBranchPrefix());
                assertEquals(700_000, config.getMaxContextForClaude());
        }

        @Test
        void should_return_empty_optional_for_missing_state_machine_arn() {
                AppConfig config = AppConfig.getInstance();

                assertTrue(config.getStateMachineArn().isEmpty(),
                                "State machine ARN should be empty Optional when not set");
        }

        @Test
        void should_return_default_context_mode() {
                AppConfig config = AppConfig.getInstance();

                assertEquals(AppConfig.ContextMode.FULL_REPO, config.getContextMode());
        }

        @Test
        void should_return_default_configurable_limits() {
                AppConfig config = AppConfig.getInstance();

                assertEquals(100000, config.getMaxFileSizeChars());
                assertEquals(3000000, config.getMaxTotalContextChars());
        }

        @Test
        void should_create_claude_config_with_defaults() {
                AppConfig config = AppConfig.getInstance();
                ClaudeConfig claudeConfig = config.getClaudeConfig();

                assertEquals(700_000, claudeConfig.maxContext());
                assertEquals("claude-opus-4-6", claudeConfig.model());
                assertEquals(32768, claudeConfig.maxTokens());
                assertEquals(0.2, claudeConfig.temperature(), 0.001);
                assertEquals("v1", claudeConfig.promptVersion());
                assertNull(claudeConfig.secretArn(),
                                "Secret ARN should be null when env var not set");
        }

        @Test
        void should_create_fetch_config_with_defaults() {
                AppConfig config = AppConfig.getInstance();
                FetchConfig fetchConfig = config.getFetchConfig();

                assertEquals(100000, fetchConfig.maxFileSizeChars());
                assertEquals(3000000L, fetchConfig.maxTotalContextChars());
                assertEquals(500_000L, fetchConfig.maxFileSizeBytes());
                assertEquals("FULL_REPO", fetchConfig.contextMode());
        }

        @Test
        void should_create_jira_config() {
                AppConfig config = AppConfig.getInstance();
                JiraConfig jiraConfig = config.getJiraConfig();

                assertNull(jiraConfig.secretArn(),
                                "Secret ARN should be null when env var not set");
                assertNull(jiraConfig.stateMachineArn(),
                                "State machine ARN should be null when env var not set");
        }

        @Test
        void should_create_bitbucket_config() {
                AppConfig config = AppConfig.getInstance();
                BitbucketConfig bitbucketConfig = config.getBitbucketConfig();

                assertNull(bitbucketConfig.secretArn(),
                                "Secret ARN should be null when env var not set");
        }
}
