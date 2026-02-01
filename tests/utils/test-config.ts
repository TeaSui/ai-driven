import * as dotenv from 'dotenv';
import * as path from 'path';

// Load environment variables from .env file
dotenv.config({ path: path.join(__dirname, '../.env') });

export interface TestConfig {
    jira: {
        baseUrl: string;
        email: string;
        apiToken: string;
        testTicketId: string;
        testProjectKey: string;
    };
    bitbucket: {
        workspace: string;
        repoSlug: string;
        username: string;
        appPassword: string;
        testRepoUrl: string;
    };
    aws: {
        region: string;
        stateMachineArn: string;
        tableName: string;
        claudeModelId: string;
    };
    test: {
        timeout: number;
        retryAttempts: number;
        cleanupAfterTests: boolean;
    };
}

export const testConfig: TestConfig = {
    jira: {
        baseUrl: process.env.TEST_JIRA_URL || 'https://test.atlassian.net',
        email: process.env.TEST_JIRA_EMAIL || '',
        apiToken: process.env.TEST_JIRA_TOKEN || '',
        testTicketId: process.env.TEST_JIRA_TICKET_ID || 'TEST-001',
        testProjectKey: process.env.TEST_JIRA_PROJECT || 'TEST'
    },
    bitbucket: {
        workspace: process.env.TEST_BB_WORKSPACE || '',
        repoSlug: process.env.TEST_BB_REPO || '',
        username: process.env.TEST_BB_USERNAME || '',
        appPassword: process.env.TEST_BB_PASSWORD || '',
        testRepoUrl: process.env.TEST_BB_REPO_URL || ''
    },
    aws: {
        region: process.env.AWS_REGION || 'us-east-1',
        stateMachineArn: process.env.TEST_STATE_MACHINE_ARN || '',
        tableName: process.env.TEST_DYNAMODB_TABLE || 'ai-driven-state',
        claudeModelId: process.env.TEST_CLAUDE_MODEL || 'claude-3-haiku-20240307'
    },
    test: {
        timeout: parseInt(process.env.TEST_TIMEOUT || '30000', 10),
        retryAttempts: parseInt(process.env.TEST_RETRY_ATTEMPTS || '3', 10),
        cleanupAfterTests: process.env.TEST_CLEANUP !== 'false'
    }
};

// Validate required configuration
export function validateTestConfig(): void {
    const errors: string[] = [];

    if (!testConfig.jira.email) errors.push('TEST_JIRA_EMAIL is required');
    if (!testConfig.jira.apiToken) errors.push('TEST_JIRA_TOKEN is required');
    if (!testConfig.bitbucket.workspace) errors.push('TEST_BB_WORKSPACE is required');
    if (!testConfig.bitbucket.username) errors.push('TEST_BB_USERNAME is required');
    if (!testConfig.bitbucket.appPassword) errors.push('TEST_BB_PASSWORD is required');

    if (errors.length > 0) {
        throw new Error(`Missing required test configuration:\n${errors.join('\n')}`);
    }
}
