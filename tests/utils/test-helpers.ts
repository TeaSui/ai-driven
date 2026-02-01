import { SFNClient, StartExecutionCommand, DescribeExecutionCommand } from '@aws-sdk/client-sfn';
import { DynamoDBClient, PutItemCommand, GetItemCommand, DeleteItemCommand } from '@aws-sdk/client-dynamodb';
import axios from 'axios';
import { testConfig } from './test-config';

/**
 * Test helper utilities for workflow automation tests
 */

// ==================== Jira Helpers ====================

export interface TicketInfo {
    ticketKey: string;
    summary: string;
    description: string;
    status: string;
    labels: string[];
}

export async function createTestTicket(data: Partial<TicketInfo>): Promise<TicketInfo> {
    const auth = Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');

    try {
        const response = await axios.post(
            `${testConfig.jira.baseUrl}/rest/api/3/issue`,
            {
                fields: {
                    project: { key: testConfig.jira.testProjectKey },
                    summary: data.summary || 'Test ticket',
                    description: {
                        type: 'doc',
                        version: 1,
                        content: [
                            {
                                type: 'paragraph',
                                content: [{ type: 'text', text: data.description || 'Test description' }]
                            }
                        ]
                    },
                    issuetype: { name: 'Task' },
                    labels: data.labels || []
                }
            },
            {
                headers: {
                    'Authorization': `Basic ${auth}`,
                    'Content-Type': 'application/json'
                }
            }
        );
        return {
            ticketKey: response.data.key,
            summary: data.summary || 'Test ticket',
            description: data.description || 'Test description',
            status: 'TO DO',
            labels: data.labels || []
        };
    } catch (error: any) {
        console.error('❌ Failed to create Jira ticket:', error.message);
        if (error.response) {
            console.error('Response data:', JSON.stringify(error.response.data, null, 2));
        }
        throw error;
    }
}

export async function addLabelToTicket(ticketKey: string, label: string): Promise<void> {
    const auth = Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');

    await axios.put(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        {
            update: {
                labels: [{ add: label }]
            }
        },
        {
            headers: {
                'Authorization': `Basic ${auth}`,
                'Content-Type': 'application/json'
            }
        }
    );
}

export async function getTicket(ticketKey: string): Promise<TicketInfo> {
    const auth = Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');

    const response = await axios.get(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        {
            headers: {
                'Authorization': `Basic ${auth}`,
                'Accept': 'application/json'
            }
        }
    );

    return {
        ticketKey: response.data.key,
        summary: response.data.fields.summary,
        description: response.data.fields.description?.content?.[0]?.content?.[0]?.text || '',
        status: response.data.fields.status.name,
        labels: response.data.fields.labels || []
    };
}

export async function deleteTicket(ticketKey: string): Promise<void> {
    const auth = Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');

    await axios.delete(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        {
            headers: {
                'Authorization': `Basic ${auth}`
            }
        }
    );
}

// ==================== Bitbucket Helpers ====================

export interface PullRequest {
    id: string;
    url: string;
    title: string;
    state: string;
}

export async function waitForPRCreation(ticketKey: string, timeout = 60000): Promise<PullRequest> {
    const startTime = Date.now();
    const branchName = `ai-ticket-${ticketKey}`;

    while (Date.now() - startTime < timeout) {
        try {
            const auth = Buffer.from(`${testConfig.bitbucket.username}:${testConfig.bitbucket.appPassword}`).toString('base64');

            const response = await axios.get(
                `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/pullrequests`,
                {
                    headers: {
                        'Authorization': `Basic ${auth}`,
                        'Accept': 'application/json'
                    }
                }
            );

            const pr = response.data.values.find((p: any) => p.source.branch.name === branchName);
            if (pr) {
                return {
                    id: pr.id,
                    url: pr.links.html.href,
                    title: pr.title,
                    state: pr.state
                };
            }
        } catch (error) {
            // Continue polling
        }

        await sleep(2000); // Poll every 2 seconds
    }

    throw new Error(`PR not created within ${timeout}ms for ticket ${ticketKey}`);
}

export async function mergePR(prId: string): Promise<void> {
    const auth = Buffer.from(`${testConfig.bitbucket.username}:${testConfig.bitbucket.appPassword}`).toString('base64');

    await axios.post(
        `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/pullrequests/${prId}/merge`,
        {},
        {
            headers: {
                'Authorization': `Basic ${auth}`,
                'Content-Type': 'application/json'
            }
        }
    );
}

export async function deleteBranch(branchName: string): Promise<void> {
    const auth = Buffer.from(`${testConfig.bitbucket.username}:${testConfig.bitbucket.appPassword}`).toString('base64');

    await axios.delete(
        `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/refs/branches/${branchName}`,
        {
            headers: {
                'Authorization': `Basic ${auth}`
            }
        }
    );
}

// ==================== AWS Helpers ====================

export interface Execution {
    executionArn: string;
    status: string;
    output?: any;
}

export async function waitForExecution(ticketKey: string, timeout = 30000): Promise<Execution> {
    const sfnClient = new SFNClient({ region: testConfig.aws.region });
    const startTime = Date.now();

    // In real implementation, we'd query executions by name or tags
    // For now, this is a placeholder that would need actual execution tracking

    while (Date.now() - startTime < timeout) {
        // TODO: Implement actual execution lookup logic
        await sleep(1000);
    }

    throw new Error(`Execution not found within ${timeout}ms for ticket ${ticketKey}`);
}

export async function waitForTicketStatus(ticketKey: string, expectedStatus: string, timeout = 30000): Promise<void> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeout) {
        const ticket = await getTicket(ticketKey);
        if (ticket.status === expectedStatus) {
            return;
        }
        await sleep(2000);
    }

    throw new Error(`Ticket ${ticketKey} did not reach status ${expectedStatus} within ${timeout}ms`);
}

// ==================== Cleanup Helpers ====================

export async function cleanupTestData(ticketKey: string): Promise<void> {
    if (!testConfig.test.cleanupAfterTests) {
        console.log(`⏭️  Skipping cleanup for ${ticketKey} (cleanup disabled)`);
        return;
    }

    try {
        // Delete branch
        const branchName = `ai-ticket-${ticketKey}`;
        await deleteBranch(branchName).catch(() => { });

        // Delete ticket
        await deleteTicket(ticketKey).catch(() => { });

        console.log(`🧹 Cleaned up test data for ${ticketKey}`);
    } catch (error) {
        console.warn(`⚠️  Cleanup failed for ${ticketKey}:`, error);
    }
}

// ==================== Utility Functions ====================

export function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

export async function retry<T>(
    fn: () => Promise<T>,
    attempts = testConfig.test.retryAttempts,
    delay = 1000
): Promise<T> {
    for (let i = 0; i < attempts; i++) {
        try {
            return await fn();
        } catch (error) {
            if (i === attempts - 1) throw error;
            await sleep(delay * (i + 1)); // Exponential backoff
        }
    }
    throw new Error('Retry failed');
}
