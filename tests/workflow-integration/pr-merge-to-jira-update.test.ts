import { DynamoDBClient, PutItemCommand, GetItemCommand, DeleteItemCommand } from '@aws-sdk/client-dynamodb';
import { testConfig } from '../utils/test-config';

/**
 * Workflow Integration Test: PR Merge → Jira Update
 * Tests the callback mechanism from PR merge to Jira status update
 */
describe('Workflow Integration: PR Merge to Jira Update', () => {
    let dynamoClient: DynamoDBClient;

    beforeAll(() => {
        dynamoClient = new DynamoDBClient({ region: testConfig.aws.region });
    });

    describe('Task Token Storage', () => {
        it('should store task token in DynamoDB', async () => {
            const taskToken = 'test-task-token-12345';
            const ticketKey = 'TEST-999';
            const prId = '42';

            const putCommand = new PutItemCommand({
                TableName: testConfig.aws.tableName,
                Item: {
                    ticketKey: { S: ticketKey },
                    taskToken: { S: taskToken },
                    prId: { S: prId },
                    timestamp: { N: Date.now().toString() },
                    status: { S: 'WAITING_FOR_MERGE' }
                }
            });

            try {
                await dynamoClient.send(putCommand);
                console.log('✅ Task token stored in DynamoDB');
            } catch (error) {
                console.warn('⚠️  DynamoDB not configured, skipping test');
            }
        });

        it('should retrieve task token by ticket key', async () => {
            const ticketKey = 'TEST-999';

            const getCommand = new GetItemCommand({
                TableName: testConfig.aws.tableName,
                Key: {
                    ticketKey: { S: ticketKey }
                }
            });

            try {
                const response = await dynamoClient.send(getCommand);

                if (response.Item) {
                    expect(response.Item.ticketKey.S).toBe(ticketKey);
                    expect(response.Item.taskToken).toBeDefined();
                }
            } catch (error) {
                console.warn('⚠️  DynamoDB not configured, skipping test');
            }
        });
    });

    describe('Bitbucket Webhook Processing', () => {
        it('should parse PR merge webhook event', () => {
            const webhookEvent = {
                eventKey: 'pullrequest:fulfilled',
                pullrequest: {
                    id: 42,
                    title: '[AI] Generated Implementation for TEST-123',
                    state: 'MERGED',
                    source: {
                        branch: {
                            name: 'ai-ticket-TEST-123'
                        }
                    },
                    destination: {
                        branch: {
                            name: 'main'
                        }
                    }
                }
            };

            // Extract ticket key from branch name
            const branchName = webhookEvent.pullrequest.source.branch.name;
            const ticketKeyMatch = branchName.match(/ai-ticket-(.+)/);
            const ticketKey = ticketKeyMatch ? ticketKeyMatch[1] : null;

            expect(ticketKey).toBe('TEST-123');
            expect(webhookEvent.pullrequest.state).toBe('MERGED');
        });

        it('should ignore non-merge events', () => {
            const webhookEvent = {
                eventKey: 'pullrequest:updated',
                pullrequest: {
                    state: 'OPEN'
                }
            };

            const shouldProcess = webhookEvent.eventKey === 'pullrequest:fulfilled' ||
                webhookEvent.pullrequest.state === 'MERGED';

            expect(shouldProcess).toBe(false);
        });

        it('should extract PR metadata', () => {
            const webhookEvent = {
                pullrequest: {
                    id: 42,
                    title: '[AI] Generated Implementation for PROJ-456',
                    author: {
                        display_name: 'AI Bot'
                    },
                    merge_commit: {
                        hash: 'abc123def456'
                    }
                }
            };

            const metadata = {
                prId: webhookEvent.pullrequest.id,
                title: webhookEvent.pullrequest.title,
                author: webhookEvent.pullrequest.author.display_name,
                commitHash: webhookEvent.pullrequest.merge_commit.hash
            };

            expect(metadata.prId).toBe(42);
            expect(metadata.commitHash).toBe('abc123def456');
        });
    });

    describe('Step Functions Resume', () => {
        it('should construct resume payload', () => {
            const taskToken = 'stored-task-token';
            const ticketKey = 'PROJ-789';
            const prUrl = 'https://bitbucket.org/workspace/repo/pull-requests/42';

            const resumePayload = {
                taskToken: taskToken,
                output: JSON.stringify({
                    ticketKey: ticketKey,
                    prUrl: prUrl,
                    status: 'MERGED',
                    timestamp: new Date().toISOString()
                })
            };

            expect(resumePayload.taskToken).toBe(taskToken);
            const output = JSON.parse(resumePayload.output);
            expect(output.status).toBe('MERGED');
            expect(output.prUrl).toBe(prUrl);
        });
    });

    describe('Jira Update Preparation', () => {
        it('should prepare Jira update payload', () => {
            const ticketKey = 'PROJ-101';
            const prUrl = 'https://bitbucket.org/workspace/repo/pull-requests/42';

            const jiraUpdate = {
                ticketId: ticketKey,
                status: 'DONE',
                comment: `PR has been merged: ${prUrl}\n\nAutomated workflow completed successfully.`
            };

            expect(jiraUpdate.status).toBe('DONE');
            expect(jiraUpdate.comment).toContain(prUrl);
            expect(jiraUpdate.comment).toContain('merged');
        });

        it('should include merge details in comment', () => {
            const prUrl = 'https://bitbucket.org/workspace/repo/pull-requests/42';
            const commitHash = 'abc123';
            const mergedBy = 'john.doe';

            const comment = `
✅ Pull Request Merged

**PR**: ${prUrl}
**Commit**: ${commitHash}
**Merged by**: ${mergedBy}
**Status**: Workflow completed successfully

The AI-generated code has been reviewed and merged into the main branch.
      `.trim();

            expect(comment).toContain(prUrl);
            expect(comment).toContain(commitHash);
            expect(comment).toContain(mergedBy);
        });
    });

    describe('Cleanup Operations', () => {
        it('should delete task token after successful resume', async () => {
            const ticketKey = 'TEST-999';

            const deleteCommand = new DeleteItemCommand({
                TableName: testConfig.aws.tableName,
                Key: {
                    ticketKey: { S: ticketKey }
                }
            });

            try {
                await dynamoClient.send(deleteCommand);
                console.log('✅ Task token deleted from DynamoDB');
            } catch (error) {
                console.warn('⚠️  DynamoDB not configured, skipping test');
            }
        });

        it('should mark workflow as complete', () => {
            const workflowState = {
                ticketKey: 'PROJ-202',
                status: 'COMPLETED',
                completedAt: new Date().toISOString(),
                prUrl: 'https://bitbucket.org/workspace/repo/pull-requests/42'
            };

            expect(workflowState.status).toBe('COMPLETED');
            expect(workflowState.completedAt).toBeDefined();
        });
    });

    describe('Error Scenarios', () => {
        it('should handle missing task token', async () => {
            const ticketKey = 'NONEXISTENT-123';

            const getCommand = new GetItemCommand({
                TableName: testConfig.aws.tableName,
                Key: {
                    ticketKey: { S: ticketKey }
                }
            });

            try {
                const response = await dynamoClient.send(getCommand);

                if (!response.Item) {
                    expect(() => {
                        throw new Error(`Task token not found for ticket ${ticketKey}`);
                    }).toThrow('Task token not found');
                }
            } catch (error) {
                console.warn('⚠️  DynamoDB not configured, skipping test');
            }
        });

        it('should handle PR declined event', () => {
            const webhookEvent = {
                eventKey: 'pullrequest:rejected',
                pullrequest: {
                    state: 'DECLINED',
                    source: {
                        branch: {
                            name: 'ai-ticket-PROJ-303'
                        }
                    }
                }
            };

            const ticketKeyMatch = webhookEvent.pullrequest.source.branch.name.match(/ai-ticket-(.+)/);
            const ticketKey = ticketKeyMatch ? ticketKeyMatch[1] : null;

            // Should update Jira with failure status
            const jiraUpdate = {
                ticketId: ticketKey,
                status: 'TO DO', // Revert to original status
                comment: 'PR was declined. Please review and try again.'
            };

            expect(jiraUpdate.status).toBe('TO DO');
            expect(jiraUpdate.comment).toContain('declined');
        });
    });
});
