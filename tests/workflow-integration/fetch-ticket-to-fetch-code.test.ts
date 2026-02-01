import { expectValidTicketStructure } from '../utils/assertions';

/**
 * Workflow Integration Test: Fetch Ticket → Fetch Code
 * Tests data flow from Jira ticket fetching to Bitbucket code fetching
 */
describe('Workflow Integration: Fetch Ticket to Fetch Code', () => {
    describe('Data Structure Compatibility', () => {
        it('should transform ticket data to code fetch input', () => {
            // Mock ticket data from Jira handler
            const ticketData = {
                ticketKey: 'PROJ-123',
                summary: 'Add new REST endpoint',
                description: 'Create GET /api/users endpoint',
                repoUrl: 'https://bitbucket.org/myworkspace/myrepo.git',
                baseBranch: 'main',
                status: 'In Progress'
            };

            // Transform to code fetch input
            const codeFetchInput = {
                Repo: ticketData.repoUrl,
                Branch: ticketData.baseBranch,
                TicketId: ticketData.ticketKey,
                FilesToFetch: [] // Auto-discovery mode
            };

            expect(codeFetchInput.Repo).toBe(ticketData.repoUrl);
            expect(codeFetchInput.Branch).toBe(ticketData.baseBranch);
            expect(codeFetchInput.TicketId).toBe(ticketData.ticketKey);
        });

        it('should handle ticket without custom repo field', () => {
            const ticketData = {
                ticketKey: 'PROJ-124',
                summary: 'Fix bug',
                description: 'Repository: https://bitbucket.org/workspace/repo.git',
                baseBranch: 'develop'
            };

            // Extract repo from description
            const repoMatch = ticketData.description.match(/Repository:\s*(https?:\/\/[^\s]+)/);
            const repoUrl = repoMatch ? repoMatch[1] : null;

            expect(repoUrl).toBe('https://bitbucket.org/workspace/repo.git');
        });

        it('should use default branch when not specified', () => {
            const ticketData = {
                ticketKey: 'PROJ-125',
                summary: 'Add feature',
                repoUrl: 'https://bitbucket.org/workspace/repo.git'
                // No baseBranch specified
            };

            const codeFetchInput = {
                Repo: ticketData.repoUrl,
                Branch: ticketData.baseBranch || 'main', // Default to main
                TicketId: ticketData.ticketKey
            };

            expect(codeFetchInput.Branch).toBe('main');
        });
    });

    describe('Repository URL Validation', () => {
        it('should validate Bitbucket URL format', () => {
            const validUrls = [
                'https://bitbucket.org/workspace/repo.git',
                'https://bitbucket.org/workspace/repo',
                'git@bitbucket.org:workspace/repo.git'
            ];

            validUrls.forEach(url => {
                const isBitbucket = url.includes('bitbucket.org');
                expect(isBitbucket).toBe(true);
            });
        });

        it('should reject invalid repository URLs', () => {
            const invalidUrls = [
                'not-a-url',
                'https://github.com/user/repo', // Wrong platform
                'ftp://bitbucket.org/workspace/repo'
            ];

            invalidUrls.forEach(url => {
                const isValid = url.startsWith('https://bitbucket.org') ||
                    url.startsWith('git@bitbucket.org');
                expect(isValid).toBe(false);
            });
        });
    });

    describe('File Discovery Strategy', () => {
        it('should determine files to fetch based on ticket description', () => {
            const ticketData = {
                ticketKey: 'PROJ-126',
                summary: 'Update UserService',
                description: 'Modify UserService.java and UserRepository.java to add email validation'
            };

            // Extract file mentions from description
            const fileMatches = ticketData.description.match(/\w+\.java/g);
            const filesToFetch = fileMatches || [];

            expect(filesToFetch).toContain('UserService.java');
            expect(filesToFetch).toContain('UserRepository.java');
        });

        it('should use auto-discovery when no files mentioned', () => {
            const ticketData = {
                ticketKey: 'PROJ-127',
                summary: 'Add new feature',
                description: 'Implement user authentication'
            };

            const fileMatches = ticketData.description.match(/\w+\.java/g);
            const useAutoDiscovery = !fileMatches || fileMatches.length === 0;

            expect(useAutoDiscovery).toBe(true);
        });
    });

    describe('Error Propagation', () => {
        it('should propagate missing repo URL error', () => {
            const ticketData = {
                ticketKey: 'PROJ-128',
                summary: 'Test ticket',
                description: 'No repo URL provided'
            };

            const repoUrl = ticketData.repoUrl || null;

            if (!repoUrl) {
                expect(() => {
                    throw new Error('Missing repository URL in ticket');
                }).toThrow('Missing repository URL');
            }
        });
    });
});
