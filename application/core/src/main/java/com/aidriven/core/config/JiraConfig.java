package com.aidriven.core.config;

/**
 * Configuration for Jira interactions and related workflows.
 */
public record JiraConfig(
        String secretArn,
        String stateMachineArn) {
}
