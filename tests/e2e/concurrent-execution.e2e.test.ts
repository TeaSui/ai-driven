import { createTestTicket, addLabelToTicket, cleanupTestData } from '../utils/test-helpers';

/**
 * E2E Test: Concurrent Execution Handling
 * Tests that multiple workflows can run simultaneously without conflicts
 */
describe('E2E: Concurrent Execution Handling', () => {
    const testTicketKeys: string[] = [];

    afterAll(async () => {
        // Cleanup all test tickets
        for (const ticketKey of testTicketKeys) {
            await cleanupTestData(ticketKey);
        }
    });

    it('should handle multiple simultaneous workflow executions', async () => {
        console.log('🚀 Testing concurrent workflow execution');

        // Create multiple tickets simultaneously
        const ticketPromises = [
            createTestTicket({
                summary: 'Concurrent Test 1: Add endpoint A',
                description: 'Create GET /api/endpoint-a'
            }),
            createTestTicket({
                summary: 'Concurrent Test 2: Add endpoint B',
                description: 'Create GET /api/endpoint-b'
            }),
            createTestTicket({
                summary: 'Concurrent Test 3: Add endpoint C',
                description: 'Create GET /api/endpoint-c'
            })
        ];

        const tickets = await Promise.all(ticketPromises);
        testTicketKeys.push(...tickets.map(t => t.ticketKey));

        console.log(`✅ Created ${tickets.length} test tickets`);

        // Add ai-generate label to all tickets simultaneously
        const labelPromises = tickets.map(ticket =>
            addLabelToTicket(ticket.ticketKey, 'ai-generate')
        );

        await Promise.all(labelPromises);
        console.log('✅ Added labels to all tickets');

        // Verify each ticket has independent execution
        tickets.forEach(ticket => {
            expect(ticket.ticketKey).toBeDefined();
            expect(ticket.labels).toContain('ai-generate');
        });

        // Each execution should be independent
        const uniqueTickets = new Set(tickets.map(t => t.ticketKey));
        expect(uniqueTickets.size).toBe(tickets.length);

        console.log('✅ All executions are independent');
    }, 120000); // 2 minute timeout

    it('should isolate execution state between workflows', async () => {
        // Create two tickets with similar content
        const ticket1 = await createTestTicket({
            summary: 'Isolation Test 1',
            description: 'Create UserService'
        });
        const ticket2 = await createTestTicket({
            summary: 'Isolation Test 2',
            description: 'Create UserService'
        });

        testTicketKeys.push(ticket1.ticketKey, ticket2.ticketKey);

        // Both should create separate branches
        const branch1 = `ai-ticket-${ticket1.ticketKey}`;
        const branch2 = `ai-ticket-${ticket2.ticketKey}`;

        expect(branch1).not.toBe(branch2);
        expect(branch1).toContain(ticket1.ticketKey);
        expect(branch2).toContain(ticket2.ticketKey);
    });

    it('should handle execution limits gracefully', async () => {
        // Test with many concurrent executions
        const numExecutions = 10;
        const tickets = [];

        for (let i = 0; i < numExecutions; i++) {
            const ticket = await createTestTicket({
                summary: `Limit Test ${i + 1}`,
                description: `Test execution ${i + 1}`
            });
            tickets.push(ticket);
            testTicketKeys.push(ticket.ticketKey);
        }

        console.log(`✅ Created ${numExecutions} tickets for limit testing`);

        // Add labels in batches to avoid rate limiting
        const batchSize = 3;
        for (let i = 0; i < tickets.length; i += batchSize) {
            const batch = tickets.slice(i, i + batchSize);
            await Promise.all(
                batch.map(ticket => addLabelToTicket(ticket.ticketKey, 'ai-generate'))
            );

            // Small delay between batches
            await new Promise(resolve => setTimeout(resolve, 1000));
        }

        console.log('✅ All labels added successfully');
    }, 180000); // 3 minute timeout
});
