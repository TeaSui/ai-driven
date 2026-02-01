import { validateTestConfig } from './test-config';

// Global test setup
beforeAll(() => {
    // Validate test configuration before running any tests
    validateTestConfig();

    console.log('🧪 Test suite initialized');
    console.log('📝 Running tests with configuration validation');
});

// Global test teardown
afterAll(() => {
    console.log('✅ Test suite completed');
});

// Increase timeout for integration and E2E tests
jest.setTimeout(30000);
