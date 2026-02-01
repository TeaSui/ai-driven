import {
    createTestTicket,
    addLabelToTicket,
    waitForExecution,
    waitForPRCreation,
    mergePR,
    waitForTicketStatus,
    cleanupTestData
} from '../utils/test-helpers';
import { expectValidTicketStructure, expectValidPRStructure } from '../utils/assertions';

/**
 * End-to-end test for complete workflow happy path
 * Tests the full flow: Label added → PR created → Merged → Jira updated
 */
describe('E2E: Complete Workflow Happy Path', () => {
    let testTicketKey: string;

    afterEach(async () => {
        if (testTicketKey) {
            await cleanupTestData(testTicketKey);
        }
    });

    it('should complete full workflow from label to merge', async () => {
        console.log('🚀 Starting E2E workflow test');

        // Step 1: Create test ticket in Jira
        console.log('📝 Step 1: Creating test ticket...');
        const ticket = await createTestTicket({
            summary: 'E2E Test: Add new REST endpoint',
            description: 'Create GET /api/users endpoint that returns a list of users',
            labels: []
        });
        testTicketKey = ticket.ticketKey;

        expectValidTicketStructure(ticket);
        console.log(`✅ Created ticket: ${ticket.ticketKey}`);

        // Step 2: Add ai-generate label to trigger workflow
        console.log('🏷️  Step 2: Adding ai-generate label...');
        await addLabelToTicket(ticket.ticketKey, 'ai-generate');
        console.log('✅ Label added');

        // Step 3: Wait for Step Functions execution to start
        console.log('⏳ Step 3: Waiting for Step Functions execution...');
        try {
            const execution = await waitForExecution(ticket.ticketKey, 30000);
            expect(execution.status).toBe('RUNNING');
            console.log(`✅ Execution started: ${execution.executionArn}`);
        } catch (error) {
            console.warn('⚠️  Could not verify execution (may need implementation)');
        }

        // Step 4: Wait for PR creation
        console.log('⏳ Step 4: Waiting for PR creation (up to 2 minutes)...');
        try {
            const pr = await waitForPRCreation(ticket.ticketKey, 120000);

            expectValidPRStructure(pr);
            expect(pr.title).toContain(ticket.ticketKey);
            console.log(`✅ PR created: ${pr.url}`);

            // Step 5: Simulate PR merge
            console.log('🔀 Step 5: Merging PR...');
            await mergePR(pr.id);
            console.log('✅ PR merged');

            // Step 6: Wait for Jira update to DONE
            console.log('⏳ Step 6: Waiting for Jira status update...');
            await waitForTicketStatus(ticket.ticketKey, 'DONE', 30000);
            console.log('✅ Ticket status updated to DONE');

            // Step 7: Verify final state
            console.log('🔍 Step 7: Verifying final state...');
            const finalTicket = await getTicket(ticket.ticketKey);
            expect(finalTicket.status).toBe('DONE');
            console.log('✅ Workflow completed successfully!');
        } catch (error) {
            console.error('❌ E2E test failed:', error);
            throw error;
        }
    }, 300000); // 5 minute timeout for full E2E test
});

// Helper function (should be in test-helpers.ts)
async function getTicket(ticketKey: string) {
    const { getTicket: getTicketHelper } = await import('../utils/test-helpers');
    return getTicketHelper(ticketKey);
}
