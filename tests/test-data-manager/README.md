# Test Data Fixtures

This directory contains test data fixtures for workflow automation tests.

## Structure

```
test-data-manager/
├── fixtures/
│   ├── jira-tickets.json      # Sample Jira ticket data
│   ├── bitbucket-prs.json     # Sample PR data
│   ├── claude-responses.json # Sample AI responses
│   └── webhooks.json          # Sample webhook events
├── generators/
│   ├── ticket-generator.ts    # Generate test tickets
│   ├── pr-generator.ts        # Generate test PRs
│   └── webhook-generator.ts   # Generate webhook events
└── cleanup.ts                 # Cleanup utilities
```

## Usage

### Load Fixtures

```typescript
import { loadFixture } from './test-data-manager';

const sampleTicket = loadFixture('jira-tickets', 'basic-ticket');
```

### Generate Test Data

```typescript
import { generateTicket } from './test-data-manager/generators/ticket-generator';

const ticket = generateTicket({
  summary: 'Test ticket',
  description: 'Test description'
});
```

### Cleanup

```typescript
import { cleanupAll } from './test-data-manager/cleanup';

afterAll(async () => {
  await cleanupAll();
});
```
