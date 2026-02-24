import {
    SFNClient,
    ListExecutionsCommand,
    DescribeExecutionCommand
} from '@aws-sdk/client-sfn';
import axios from 'axios';
import { testConfig } from './test-config';

/**
 * Test helper utilities for workflow automation tests.
 *
 * Sections:
 *  - Jira helpers  (createTestTicket, addLabelToTicket, getTicket, deleteTicket, waitForTicketStatus)
 *  - GitHub helpers (waitForGitHubPRCreation, getGitHubPR, mergeGitHubPR, deleteGitHubBranch,
 *                    getGitHubBranch, listGitHubPRs)
 *  - AWS / SFN helpers (waitForExecution, waitForSfnExecutionToComplete)
 *  - Bitbucket helpers (legacy; left intact for backward compatibility)
 *  - Cleanup helpers
 *  - Utility functions (sleep, retry)
 */

// ==================== Jira Helpers ====================

export interface TicketInfo {
    ticketKey: string;
    summary: string;
    description: string;
    status: string;
    labels: string[];
}

function jiraAuthHeader(): string {
    return 'Basic ' + Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');
}

export async function createTestTicket(data: Partial<TicketInfo>): Promise<TicketInfo> {
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
            { headers: { Authorization: jiraAuthHeader(), 'Content-Type': 'application/json' } }
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

export async function addLabelsToTicket(ticketKey: string, labels: string[]): Promise<void> {
    await axios.put(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        { fields: { labels } },
        {
            headers: {
                Authorization: jiraAuthHeader(),
                Accept: 'application/json',
                'Content-Type': 'application/json'
            }
        }
    );
}

export async function addLabelToTicket(ticketKey: string, label: string): Promise<void> {
    return addLabelsToTicket(ticketKey, [label]);
}

export async function getTicket(ticketKey: string): Promise<TicketInfo> {
    const response = await axios.get(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        { headers: { Authorization: jiraAuthHeader(), Accept: 'application/json' } }
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
    await axios.delete(
        `${testConfig.jira.baseUrl}/rest/api/3/issue/${ticketKey}`,
        { headers: { Authorization: jiraAuthHeader() } }
    );
}

export async function waitForTicketStatus(
    ticketKey: string,
    expectedStatus: string,
    timeout = 30000
): Promise<void> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeout) {
        const ticket = await getTicket(ticketKey);
        if (ticket.status.toUpperCase() === expectedStatus.toUpperCase()) {
            return;
        }
        await sleep(2000);
    }

    throw new Error(`Ticket ${ticketKey} did not reach status "${expectedStatus}" within ${timeout}ms`);
}

// ==================== GitHub Helpers ====================

export interface GitHubPR {
    id: number;
    number: number;
    url: string;
    title: string;
    state: string;
    body: string;
    headBranch: string;
    baseBranch: string;
}

function githubHeaders(): Record<string, string> {
    return {
        Authorization: `Bearer ${testConfig.github.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28'
    };
}

function githubRepoBase(): string {
    return `${testConfig.github.apiBase}/repos/${testConfig.github.owner}/${testConfig.github.repo}`;
}

/**
 * List open pull requests in the configured GitHub repository.
 */
export async function listGitHubPRs(state: 'open' | 'closed' | 'all' = 'open'): Promise<GitHubPR[]> {
    const response = await axios.get(`${githubRepoBase()}/pulls`, {
        headers: githubHeaders(),
        params: { state, per_page: 100 }
    });

    return response.data.map((pr: any) => ({
        id: pr.id,
        number: pr.number,
        url: pr.html_url,
        title: pr.title,
        state: pr.state,
        body: pr.body || '',
        headBranch: pr.head.ref,
        baseBranch: pr.base.ref
    }));
}

/**
 * Get a single GitHub PR by PR number.
 */
export async function getGitHubPR(prNumber: number): Promise<GitHubPR> {
    const response = await axios.get(`${githubRepoBase()}/pulls/${prNumber}`, {
        headers: githubHeaders()
    });
    const pr = response.data;
    return {
        id: pr.id,
        number: pr.number,
        url: pr.html_url,
        title: pr.title,
        state: pr.state,
        body: pr.body || '',
        headBranch: pr.head.ref,
        baseBranch: pr.base.ref
    };
}

/**
 * Poll GitHub PRs until one is found whose head branch name contains the ticket key.
 * Returns when found or throws after timeout.
 */
export async function waitForGitHubPRCreation(
    ticketKey: string,
    timeout = 300000
): Promise<GitHubPR> {
    const startTime = Date.now();
    const keyLower = ticketKey.toLowerCase();

    while (Date.now() - startTime < timeout) {
        try {
            const prs = await listGitHubPRs('open');
            const match = prs.find(
                pr =>
                    pr.headBranch.toLowerCase().includes(keyLower) ||
                    pr.title.toLowerCase().includes(keyLower)
            );
            if (match) {
                return match;
            }
        } catch (err: any) {
            // Transient network or rate-limit errors — keep polling
            console.warn('⚠️  GitHub PR poll error (retrying):', err.message);
        }
        await sleep(5000);
    }

    throw new Error(
        `No GitHub PR created within ${timeout}ms for ticket ${ticketKey}`
    );
}

/**
 * Merge a GitHub pull request (squash merge).
 */
export async function mergeGitHubPR(prNumber: number, commitTitle?: string): Promise<void> {
    await axios.put(
        `${githubRepoBase()}/pulls/${prNumber}/merge`,
        {
            merge_method: 'squash',
            commit_title: commitTitle || `Squash merge PR #${prNumber}`
        },
        { headers: githubHeaders() }
    );
}

/**
 * Delete a branch in the configured GitHub repository.
 * Silently ignores 404 (branch already gone).
 */
export async function deleteGitHubBranch(branchName: string): Promise<void> {
    try {
        await axios.delete(
            `${githubRepoBase()}/git/refs/heads/${encodeURIComponent(branchName)}`,
            { headers: githubHeaders() }
        );
    } catch (err: any) {
        if (err.response?.status !== 404) {
            throw err;
        }
    }
}

/**
 * Returns true if a branch with the given name exists in the repo.
 */
export async function getGitHubBranch(branchName: string): Promise<boolean> {
    try {
        await axios.get(`${githubRepoBase()}/branches/${encodeURIComponent(branchName)}`, {
            headers: githubHeaders()
        });
        return true;
    } catch (err: any) {
        if (err.response?.status === 404) {
            return false;
        }
        throw err;
    }
}

// ==================== AWS / SFN Helpers ====================

export interface Execution {
    executionArn: string;
    name: string;
    status: string;
    output?: any;
}

const sfnClient = new SFNClient({ region: testConfig.aws.region });

/**
 * Poll Step Functions until an execution whose name starts with `${ticketKey}-`
 * appears among recent executions.  Returns as soon as one is found in any status.
 *
 * The execution name is set by JiraWebhookHandler as:
 *   `${ticketKey}-${uuid.substring(0, 8)}`
 * so we can reliably filter by prefix.
 */
export async function waitForExecution(
    ticketKey: string,
    timeout = 60000
): Promise<Execution> {
    if (!testConfig.aws.stateMachineArn) {
        throw new Error('TEST_STATE_MACHINE_ARN must be set to use waitForExecution');
    }

    const startTime = Date.now();

    while (Date.now() - startTime < timeout) {
        try {
            const result = await sfnClient.send(
                new ListExecutionsCommand({
                    stateMachineArn: testConfig.aws.stateMachineArn,
                    maxResults: 50
                })
            );

            const match = (result.executions || []).find(e =>
                e.name?.startsWith(`${ticketKey}-`)
            );

            if (match) {
                return {
                    executionArn: match.executionArn!,
                    name: match.name!,
                    status: match.status!
                };
            }
        } catch (err: any) {
            console.warn('⚠️  SFN list executions error (retrying):', err.message);
        }

        await sleep(2000);
    }

    throw new Error(
        `No SFN execution found within ${timeout}ms for ticket ${ticketKey}`
    );
}

/**
 * Poll a specific execution until it reaches a terminal state
 * (SUCCEEDED, FAILED, TIMED_OUT, ABORTED).
 */
export async function waitForSfnExecutionToComplete(
    executionArn: string,
    timeout = 600000
): Promise<Execution> {
    const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABORTED']);
    const startTime = Date.now();

    while (Date.now() - startTime < timeout) {
        const result = await sfnClient.send(
            new DescribeExecutionCommand({ executionArn })
        );
        const status = result.status!;

        if (TERMINAL.has(status)) {
            let output: any;
            try {
                output = result.output ? JSON.parse(result.output) : undefined;
            } catch {
                output = result.output;
            }
            return { executionArn, name: result.name!, status, output };
        }

        await sleep(5000);
    }

    throw new Error(
        `Execution ${executionArn} did not reach terminal state within ${timeout}ms`
    );
}

// ==================== Bitbucket Helpers (legacy) ====================

export interface PullRequest {
    id: string;
    url: string;
    title: string;
    state: string;
}

function bitbucketAuthHeader(): string {
    return (
        'Basic ' +
        Buffer.from(
            `${testConfig.bitbucket.username}:${testConfig.bitbucket.appPassword}`
        ).toString('base64')
    );
}

export async function waitForPRCreation(
    ticketKey: string,
    timeout = 60000
): Promise<PullRequest> {
    const startTime = Date.now();
    const branchName = `ai/${ticketKey.toLowerCase()}`;

    while (Date.now() - startTime < timeout) {
        try {
            const response = await axios.get(
                `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/pullrequests`,
                { headers: { Authorization: bitbucketAuthHeader(), Accept: 'application/json' } }
            );

            const pr = response.data.values.find(
                (p: any) => p.source.branch.name === branchName
            );
            if (pr) {
                return {
                    id: pr.id,
                    url: pr.links.html.href,
                    title: pr.title,
                    state: pr.state
                };
            }
        } catch {
            // Transient error — keep polling
        }

        await sleep(2000);
    }

    throw new Error(`PR not created within ${timeout}ms for ticket ${ticketKey}`);
}

export async function mergePR(prId: string): Promise<void> {
    await axios.post(
        `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/pullrequests/${prId}/merge`,
        {},
        { headers: { Authorization: bitbucketAuthHeader(), 'Content-Type': 'application/json' } }
    );
}

export async function deleteBranch(branchName: string): Promise<void> {
    await axios.delete(
        `https://api.bitbucket.org/2.0/repositories/${testConfig.bitbucket.workspace}/${testConfig.bitbucket.repoSlug}/refs/branches/${branchName}`,
        { headers: { Authorization: bitbucketAuthHeader() } }
    );
}

// ==================== Cleanup Helpers ====================

export async function cleanupTestData(ticketKey: string): Promise<void> {
    if (!testConfig.test.cleanupAfterTests) {
        console.log(`⏭️  Skipping cleanup for ${ticketKey} (cleanup disabled)`);
        return;
    }

    try {
        const branchName = `ai-ticket-${ticketKey.toLowerCase()}`;
        await deleteGitHubBranch(branchName).catch(() => {});
        await deleteTicket(ticketKey).catch(() => {});
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
            await sleep(delay * (i + 1)); // linear back-off
        }
    }
    throw new Error('retry exhausted');
}
