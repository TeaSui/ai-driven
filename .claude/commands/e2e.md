# E2E - End-to-End Test Generation

Generate comprehensive end-to-end tests for user flows.

## Workflow

### Step 1: Identify User Flows
- Map critical user journeys
- Document expected behavior at each step
- Identify entry and exit points
- Note data requirements

### Step 2: Set Up Test Structure
- Create test file in appropriate directory
- Set up test fixtures and data
- Configure browser/environment settings
- Plan cleanup procedures

### Step 3: Generate Tests
For each user flow, create tests that:
- Navigate to starting point
- Perform user actions
- Assert expected outcomes
- Handle async operations
- Clean up test data

### Step 4: Add Assertions
- Verify page content
- Check URL changes
- Validate form submissions
- Confirm API responses
- Assert visual elements

### Step 5: Run and Verify
- Execute tests locally
- Fix flaky tests
- Ensure tests are deterministic
- Add to CI pipeline

## Playwright Template
```typescript
import { test, expect } from '@playwright/test';

test.describe('Feature Name', () => {
  test.beforeEach(async ({ page }) => {
    // Setup: navigate, login, seed data
  });

  test('should [expected behavior] when [action]', async ({ page }) => {
    // Arrange
    await page.goto('/path');

    // Act
    await page.click('button[data-testid="submit"]');

    // Assert
    await expect(page.locator('.success')).toBeVisible();
  });
});
```

## Cypress Template
```typescript
describe('Feature Name', () => {
  beforeEach(() => {
    cy.visit('/path');
  });

  it('should [expected behavior] when [action]', () => {
    cy.get('[data-testid="input"]').type('value');
    cy.get('[data-testid="submit"]').click();
    cy.contains('Success').should('be.visible');
  });
});
```

## Best Practices
- Use data-testid attributes for selectors
- Avoid hard-coded waits, use proper assertions
- Test one user flow per test
- Keep tests independent
- Use page object pattern for maintainability
