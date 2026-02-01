module.exports = {
    preset: 'ts-jest',
    testEnvironment: 'node',
    roots: ['<rootDir>'],
    testMatch: ['**/*.test.ts', '**/*.spec.ts'],
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
    collectCoverageFrom: [
        '**/*.ts',
        '!**/*.d.ts',
        '!**/node_modules/**',
        '!**/dist/**',
        '!**/coverage/**'
    ],
    coverageThreshold: {
        global: {
            branches: 80,
            functions: 80,
            lines: 80,
            statements: 80
        }
    },
    coverageReporters: ['text', 'lcov', 'html'],
    testTimeout: 30000, // 30 seconds for integration tests
    setupFilesAfterEnv: ['<rootDir>/utils/test-setup.ts'],
    globals: {
        'ts-jest': {
            tsconfig: {
                esModuleInterop: true,
                allowSyntheticDefaultImports: true
            }
        }
    }
};
