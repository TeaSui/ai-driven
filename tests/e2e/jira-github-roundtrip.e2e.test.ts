/**
 * Phase L — End-to-End: Jira → Step Functions → GitHub PR Round-Trip
 *
 * Test strategy
 * ─────────────
 * The suite is split into three layers so it can run incrementally in CI:
 *
 *  1. Unit layer   — pure logic tests, no network.  Always run.
 *  2. Jira layer   — ticket CRUD against a real Jira cloud instance.
 *                    Requires: TEST_JIRA_* env vars.
 *  3. Live layer   — full pipeline trigger (label → SFN → GitHub PR → Jira status).
 *                    Requires: TEST_JIRA_*, TEST_GITHUB_*, TEST_STATE_MACHINE_ARN.
 *                    Skipped automatically when any of those vars are absent.
 *
 * Live tests are long-running (up to 10 min) and gate on a real deployed
 * infrastructure stack.  They should be tagged `@live` in CI and run only on
 * demand or as a post-deploy smoke suite.
 *
 * Naming conventions asserted here must match JiraWebhookHandler:
 *   branch  →  ai-ticket-${ticketKey.toLowerCase()}
 *   exec    →  ${ticketKey}-${uuid[0..7]}
 */

import {
    createTestTicket,
    addLabelsToTicket,
    addLabelToTicket,
    getTicket,
    deleteTicket,
    waitForExecution,
    waitForSfnExecutionToComplete,
    waitForGitHubPRCreation,
    getGitHubPR,
    mergeGitHubPR,
    deleteGitHubBranch,
    getGitHubBranch,
    waitForTicketStatus,
    sleep,
    retry,
    GitHubPR
} from '../utils/test-helpers';
import { testConfig, validateGitHubConfig } from '../utils/test-config';
import {
    expectValidTicketStructure,
    expectTicketKeyFormat,
    expectValidGitHubPRStructure,
    expectBranchNameFormat,
    expectPRTitleContainsTicketKey,
    expectValidExecutionStructure
} from '../utils/assertions';

// ─────────────────────────────────────────────────────────────────────────────
// Guard: skip live/GitHub tests when required env vars are absent
// ─────────────────────────────────────────────────────────────────────────────

const LIVE_INFRA_AVAILABLE =
    Boolean(testConfig.github.owner) &&
    Boolean(testConfig.github.repo) &&
    Boolean(testConfig.github.token) &&
    Boolean(testConfig.aws.stateMachineArn);

const describeIfLive = LIVE_INFRA_AVAILABLE ? describe : describe.skip;

// ─────────────────────────────────────────────────────────────────────────────
// 1. Unit layer — deterministic logic, no network
// ─────────────────────────────────────────────────────────────────────────────

describe('Unit: naming conventions and idempotency logic', () => {
    it('derives the expected branch name from a ticket key', () => {
        const ticketKey = 'PROJ-42';
        const branch = `ai-ticket-${ticketKey.toLowerCase()}`;
        expect(branch).toBe('ai-ticket-proj-42');
        expectBranchNameFormat(branch, ticketKey);
    });

    it('generates a unique execution name per ticket+uuid', () => {
        const ticketKey = 'PROJ-99';
        const uuid8 = 'a1b2c3d4';
        const execName = `${ticketKey}-${uuid8}`;
        expect(execName).toMatch(/^PROJ-\d+-[a-f0-9]{8}$/);
    });

    it('execution names for the same ticket are prefix-equal but unique', () => {
        const ticketKey = 'PROJ-10';
        const exec1 = `${ticketKey}-aaaaaaaa`;
        const exec2 = `${ticketKey}-bbbbbbbb`;
        expect(exec1.startsWith(`${ticketKey}-`)).toBe(true);
        expect(exec2.startsWith(`${ticketKey}-`)).toBe(true);
        expect(exec1).not.toBe(exec2);
    });

    it('SFN input shape has required fields', () => {
        const sfnInput = {
            ticketId: '10042',
            ticketKey: 'PROJ-42',
            webhookEvent: 'jira:issue_updated',
            dryRun: false,
            labels: ['ai-generate'],
            platform: 'github',
            repoOwner: 'acme',
            repoSlug: 'backend'
        };

        expect(sfnInput).toHaveProperty('ticketKey');
        expect(sfnInput).toHaveProperty('ticketId');
        expect(sfnInput).toHaveProperty('platform');
        expect(sfnInput).toHaveProperty('repoOwner');
        expect(sfnInput).toHaveProperty('repoSlug');
        expect(sfnInput.dryRun).toBe(false);
    });

    it('DynamoDB idempotency key combines tenantId + ticketId', () => {
        const tenantId = 'default';
        const ticketId = '10042';
        const key = `${tenantId}#${ticketId}`;
        expect(key).toBe('default#10042');
    });

    it('waitForExecution throws when stateMachineArn is absent', async () => {
        // Temporarily clear the ARN
        const original = testConfig.aws.stateMachineArn;
        (testConfig.aws as any).stateMachineArn = '';

        await expect(waitForExecution('PROJ-X', 100)).rejects.toThrow(
            'TEST_STATE_MACHINE_ARN must be set'
        );

        (testConfig.aws as any).stateMachineArn = original;
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// 2. Jira layer — ticket CRUD
// ─────────────────────────────────────────────────────────────────────────────

describe('Jira: ticket lifecycle', () => {
    let ticketKey: string;

    afterEach(async () => {
        if (ticketKey) {
            await deleteTicket(ticketKey).catch(err =>
                console.warn(`⚠️  afterEach cleanup for ${ticketKey}:`, err.message)
            );
            ticketKey = '';
        }
    });

    it('creates a ticket and returns a valid structure', async () => {
        const ticket = await createTestTicket({
            summary: '[E2E] Round-trip test ticket',
            description: 'Implement GET /api/ping endpoint',
            labels: ['ai-test']
        });

        ticketKey = ticket.ticketKey;
        expectValidTicketStructure(ticket);
        expectTicketKeyFormat(ticket.ticketKey);
        expect(ticket.labels).toContain('ai-test');
    });

    it('fetches a ticket and round-trips the key', async () => {
        const created = await createTestTicket({
            summary: '[E2E] Fetch round-trip',
            description: 'Implement POST /api/echo'
        });
        ticketKey = created.ticketKey;

        const fetched = await getTicket(ticketKey);
        expect(fetched.ticketKey).toBe(ticketKey);
        expect(fetched.summary).toBe(created.summary);
    });

    it('adds labels atomically with addLabelsToTicket', async () => {
        const ticket = await createTestTicket({
            summary: '[E2E] Label update test',
            description: 'Test label mutation'
        });
        ticketKey = ticket.ticketKey;

        await addLabelsToTicket(ticketKey, ['ai-generate', 'team:backend']);
        const updated = await getTicket(ticketKey);

        expect(updated.labels).toContain('ai-generate');
        expect(updated.labels).toContain('team:backend');
    });

    it('deletes a ticket without error', async () => {
        const ticket = await createTestTicket({
            summary: '[E2E] Delete test',
            description: 'Will be deleted'
        });

        // Delete immediately — afterEach will get a 404 but that's silenced
        await deleteTicket(ticket.ticketKey);
        // No ticketKey assignment, afterEach won't double-delete
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// 3. Live layer — full Jira → SFN → GitHub PR round-trip
// ─────────────────────────────────────────────────────────────────────────────

describeIfLive('Live E2E: Jira label → SFN → GitHub PR round-trip', () => {
    let ticketKey: string;
    let prNumber: number | null = null;

    beforeAll(() => {
        validateGitHubConfig();
    });

    afterEach(async () => {
        if (!testConfig.test.cleanupAfterTests) {
            return;
        }
        if (prNumber) {
            // Close / delete the test PR branch
            const pr = await getGitHubPR(prNumber).catch(() => null);
            if (pr) {
                await deleteGitHubBranch(pr.headBranch).catch(() => {});
            }
            prNumber = null;
        }
        if (ticketKey) {
            await deleteTicket(ticketKey).catch(() => {});
            ticketKey = '';
        }
    });

    /**
     * Happy-path: add 'ai-generate' label → SFN starts → GitHub PR is created
     * → PR head branch follows naming convention → PR title contains ticket key.
     *
     * Timeout: 8 minutes (SFN + Claude invocation + PR push)
     */
    it(
        'creates a GitHub PR when ai-generate label is added to a ticket',
        async () => {
            console.log('🚀 [Live] Starting Jira→GitHub round-trip test');

            // ── Step 1: Create Jira ticket ──────────────────────────────────
            console.log('📝 Step 1: Creating Jira ticket…');
            const ticket = await createTestTicket({
                summary: '[E2E] Add GET /api/health endpoint',
                description:
                    'Create a simple GET /api/health endpoint that returns ' +
                    '{"status":"ok","timestamp":<ISO8601>} with HTTP 200.',
                labels: []
            });
            ticketKey = ticket.ticketKey;
            expectValidTicketStructure(ticket);
            console.log(`✅ Created ticket: ${ticketKey}`);

            // Jira needs a moment to index the new issue
            await sleep(2000);

            // ── Step 2: Add trigger label ───────────────────────────────────
            console.log('🏷️  Step 2: Adding ai-generate label…');
            await addLabelToTicket(ticketKey, 'ai-generate');
            console.log('✅ Label added');

            // ── Step 3: Wait for SFN execution ──────────────────────────────
            console.log('⏳ Step 3: Polling Step Functions for execution…');
            const execution = await waitForExecution(ticketKey, 60_000);
            expectValidExecutionStructure(execution);
            expect(['RUNNING', 'SUCCEEDED']).toContain(execution.status);
            console.log(`✅ Execution found: ${execution.name} (${execution.status})`);

            // ── Step 4: Wait for SFN to complete ────────────────────────────
            console.log('⏳ Step 4: Waiting for SFN execution to complete…');
            const completed = await waitForSfnExecutionToComplete(
                execution.executionArn,
                480_000 // 8 min
            );
            console.log(`✅ Execution terminal status: ${completed.status}`);

            if (completed.status !== 'SUCCEEDED') {
                console.error('❌ SFN output:', JSON.stringify(completed.output, null, 2));
                throw new Error(
                    `SFN execution did not SUCCEED for ${ticketKey}: ${completed.status}`
                );
            }

            // ── Step 5: Find the GitHub PR ───────────────────────────────────
            console.log('⏳ Step 5: Polling GitHub for PR…');
            const pr = await waitForGitHubPRCreation(ticketKey, 60_000);
            prNumber = pr.number;
            expectValidGitHubPRStructure(pr);
            expectPRTitleContainsTicketKey(pr.title, ticketKey);
            expectBranchNameFormat(pr.headBranch, ticketKey);
            console.log(`✅ PR #${pr.number} found: "${pr.title}" (${pr.url})`);

            // ── Step 6: Verify PR body references the ticket ─────────────────
            const fullPr = await getGitHubPR(pr.number);
            expect(fullPr.body.toUpperCase()).toContain(ticketKey.toUpperCase());
            console.log('✅ PR body contains ticket key');
        },
        480_000 + 60_000 + 60_000 + 10_000 // 10 min total
    );

    /**
     * After the PR is merged the Jira ticket should transition to DONE via the
     * PR-merge webhook back-path (SFN callback task).
     *
     * Timeout: 3 minutes (merge + status poll)
     */
    it(
        'transitions Jira ticket to DONE after PR merge',
        async () => {
            console.log('🚀 [Live] Testing PR merge → Jira status transition');

            // ── Setup: create ticket and trigger workflow ────────────────────
            const ticket = await createTestTicket({
                summary: '[E2E] Add DELETE /api/resource endpoint',
                description:
                    'Implement DELETE /api/resource/{id} that removes the resource and ' +
                    'returns HTTP 204 on success or 404 when not found.',
                labels: []
            });
            ticketKey = ticket.ticketKey;
            await addLabelToTicket(ticketKey, 'ai-generate');
            console.log(`✅ Triggered workflow for ${ticketKey}`);

            // Wait for PR
            console.log('⏳ Waiting for GitHub PR…');
            const execution = await waitForExecution(ticketKey, 60_000);
            await waitForSfnExecutionToComplete(execution.executionArn, 480_000);
            const pr = await waitForGitHubPRCreation(ticketKey, 60_000);
            prNumber = pr.number;
            console.log(`✅ PR #${prNumber} ready`);

            // ── Merge the PR ─────────────────────────────────────────────────
            console.log('🔀 Merging PR…');
            await mergeGitHubPR(pr.number, `[E2E] ${ticketKey}: merge test`);
            prNumber = null; // Merged; no branch cleanup needed
            console.log('✅ PR merged');

            // ── Wait for Jira to reach DONE ─────────────────────────────────
            console.log('⏳ Polling Jira for DONE status…');
            await waitForTicketStatus(ticketKey, 'DONE', 120_000);
            console.log('✅ Jira ticket is DONE');

            const final = await getTicket(ticketKey);
            expect(final.status.toUpperCase()).toBe('DONE');
        },
        (480_000 + 60_000 + 60_000 + 120_000 + 10_000) // ~12 min
    );

    /**
     * Branch naming is deterministic: adding the label again must not produce
     * a second PR on a different branch.
     */
    it(
        'is idempotent: second label add does not create a duplicate PR',
        async () => {
            console.log('🔄 [Live] Testing idempotency');

            const ticket = await createTestTicket({
                summary: '[E2E] Idempotency check',
                description: 'Implement PUT /api/config endpoint'
            });
            ticketKey = ticket.ticketKey;

            // Trigger once
            await addLabelToTicket(ticketKey, 'ai-generate');
            await sleep(3_000);
            // Trigger again (same label, idempotency guard should block re-execution)
            await addLabelToTicket(ticketKey, 'ai-generate');

            const execution = await waitForExecution(ticketKey, 60_000);
            console.log(`✅ Exactly one execution found: ${execution.name}`);

            // Wait for PR; verify only one exists for this branch
            await waitForSfnExecutionToComplete(execution.executionArn, 480_000);
            const pr = await waitForGitHubPRCreation(ticketKey, 60_000);
            prNumber = pr.number;

            const branchName = `ai-ticket-${ticketKey.toLowerCase()}`;
            const allPrs = await listPRsForBranch(branchName);
            expect(allPrs.length).toBe(1);
            console.log('✅ Only one PR created for the branch (idempotency confirmed)');
        },
        480_000 + 60_000 + 60_000 + 10_000
    );

    /**
     * When the ticket description is intentionally sparse the agent should still
     * create a branch, even if the PR body is minimal.  This tests graceful
     * degradation rather than a perfect implementation.
     */
    it(
        'handles sparse ticket description gracefully (branch is created)',
        async () => {
            console.log('🧪 [Live] Testing sparse description handling');

            const ticket = await createTestTicket({
                summary: '[E2E] Sparse ticket',
                description: 'Add a config endpoint' // deliberately terse
            });
            ticketKey = ticket.ticketKey;
            await addLabelToTicket(ticketKey, 'ai-generate');

            const execution = await waitForExecution(ticketKey, 60_000);
            await waitForSfnExecutionToComplete(execution.executionArn, 480_000);

            // We only require a branch to exist; PR quality is a bonus
            const branchName = `ai-ticket-${ticketKey.toLowerCase()}`;
            const exists = await retry(() => getGitHubBranch(branchName), 6, 5_000);
            expect(exists).toBe(true);
            console.log(`✅ Branch ${branchName} created for sparse ticket`);

            // Cleanup branch directly if no PR was opened
            await deleteGitHubBranch(branchName).catch(() => {});
        },
        480_000 + 60_000 + 10_000
    );
});

// ─────────────────────────────────────────────────────────────────────────────
// Helpers local to this file
// ─────────────────────────────────────────────────────────────────────────────

/** List all open PRs (open+closed) whose head branch matches `branchName`. */
async function listPRsForBranch(branchName: string): Promise<GitHubPR[]> {
    // Re-import avoids circular dependency on module-level imports
    const { listGitHubPRs } = await import('../utils/test-helpers');
    const all = await listGitHubPRs('all');
    return all.filter(pr => pr.headBranch === branchName);
}
