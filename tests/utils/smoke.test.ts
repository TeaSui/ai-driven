/**
 * Simple smoke test to verify Jest setup is working
 */
describe('Test Infrastructure Smoke Test', () => {
    it('should run basic test', () => {
        expect(true).toBe(true);
    });

    it('should have access to test utilities', () => {
        const { testConfig } = require('../utils/test-config');
        expect(testConfig).toBeDefined();
        expect(testConfig.jira).toBeDefined();
        expect(testConfig.bitbucket).toBeDefined();
        expect(testConfig.aws).toBeDefined();
    });

    it('should have access to assertions', () => {
        const assertions = require('../utils/assertions');
        expect(assertions.expectValidTicketStructure).toBeDefined();
        expect(assertions.expectValidPRStructure).toBeDefined();
    });

    it('should have access to test helpers', () => {
        const helpers = require('../utils/test-helpers');
        expect(helpers.sleep).toBeDefined();
        expect(helpers.retry).toBeDefined();
    });
});
