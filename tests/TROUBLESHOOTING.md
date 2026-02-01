# Workflow Automation Tests - Troubleshooting Guide

## Common Issues and Solutions

### 1. Configuration Errors

#### Missing Environment Variables
**Error**: `Missing required test configuration: TEST_JIRA_EMAIL is required`

**Solution**:
```bash
cd tests
cp .env.example .env
# Edit .env with your actual credentials
```

#### Invalid Credentials
**Error**: `401 Unauthorized` or `Authentication failed`

**Solution**:
- Verify Jira API token is valid
- Verify Bitbucket app password is valid
- Check AWS credentials have correct permissions

### 2. Test Failures

#### Bedrock Invocation Fails
**Error**: `BedrockRuntimeClient invocation failed`

**Possible Causes**:
1. Invalid model ID
2. Insufficient AWS permissions
3. Region not supported

**Solution**:
```bash
# Verify model ID
aws bedrock list-foundation-models --region us-east-1

# Check IAM permissions
aws iam get-user

# Test Bedrock access
aws bedrock-runtime invoke-model \
  --model-id us.deepseek.deepseek-v3-v1:0 \
  --body '{"messages":[{"role":"user","content":"test"}],"max_tokens":100}' \
  --region us-east-1 \
  output.json
```

#### DynamoDB Access Denied
**Error**: `AccessDeniedException` when accessing DynamoDB

**Solution**:
```bash
# Add DynamoDB permissions to IAM role
aws iam attach-role-policy \
  --role-name YourTestRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess
```

#### Step Functions Not Found
**Error**: `StateMachineDoesNotExist`

**Solution**:
- Verify `TEST_STATE_MACHINE_ARN` is correct
- Ensure Step Functions state machine is deployed
- Check region matches

### 3. Network Issues

#### Timeout Errors
**Error**: `Test timeout exceeded`

**Solution**:
```javascript
// Increase timeout in jest.config.js
module.exports = {
  testTimeout: 60000 // 60 seconds
};

// Or per-test
it('long running test', async () => {
  // test code
}, 120000); // 2 minutes
```

#### Rate Limiting
**Error**: `429 Too Many Requests`

**Solution**:
- Add delays between API calls
- Use retry logic with exponential backoff
- Reduce concurrent test execution

```typescript
// Use retry helper
await retry(async () => {
  return await jiraClient.getTicket(ticketId);
}, 3, 2000); // 3 attempts, 2s delay
```

### 4. Test Data Issues

#### Cleanup Failures
**Error**: Test data not cleaned up after test

**Solution**:
```bash
# Enable cleanup
export TEST_CLEANUP=true

# Manual cleanup
cd tests
npm test -- --testPathPattern=cleanup
```

#### Duplicate Test Data
**Error**: `Branch already exists` or `Ticket already exists`

**Solution**:
- Use unique ticket IDs (include timestamp)
- Clean up before running tests
- Check for orphaned test data

```typescript
// Generate unique ticket ID
const uniqueId = `TEST-${Date.now()}`;
```

### 5. CI/CD Issues

#### GitHub Actions Secrets Not Set
**Error**: Tests fail in CI but pass locally

**Solution**:
1. Go to repository Settings → Secrets and variables → Actions
2. Add required secrets:
   - `TEST_JIRA_URL`
   - `TEST_JIRA_EMAIL`
   - `TEST_JIRA_TOKEN`
   - `TEST_BB_WORKSPACE`
   - `TEST_BB_USERNAME`
   - `TEST_BB_PASSWORD`
   - `AWS_ACCESS_KEY_ID`
   - `AWS_SECRET_ACCESS_KEY`

#### Coverage Upload Fails
**Error**: `Codecov upload failed`

**Solution**:
```yaml
# Add Codecov token to secrets
- name: Upload coverage
  uses: codecov/codecov-action@v3
  with:
    token: ${{ secrets.CODECOV_TOKEN }}
```

### 6. Performance Issues

#### Tests Running Slowly
**Symptoms**: Tests take \u003e5 minutes to complete

**Solutions**:
1. Run tests in parallel:
```bash
npm test -- --maxWorkers=4
```

2. Skip E2E tests during development:
```bash
npm run test:integration # Only integration tests
```

3. Use test filtering:
```bash
npm test -- --testPathPattern=specific-test
```

### 7. Debugging Tips

#### Enable Verbose Logging
```bash
# Run with debug output
DEBUG=* npm test

# Jest verbose mode
npm test -- --verbose
```

#### Inspect Test State
```typescript
// Add console.log in tests
console.log('Current state:', JSON.stringify(state, null, 2));

// Use debugger
debugger; // Run with node --inspect
```

#### Check Test Artifacts
```bash
# View coverage report
open tests/coverage/lcov-report/index.html

# Check test logs
cat tests/*.log
```

## Getting Help

### Check Documentation
- [Jest Documentation](https://jestjs.io/docs/getting-started)
- [AWS SDK Documentation](https://docs.aws.amazon.com/sdk-for-javascript/)
- [Jira REST API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/)
- [Bitbucket API](https://developer.atlassian.com/cloud/bitbucket/rest/)

### Common Commands
```bash
# Run specific test file
npm test -- tests/e2e/happy-path.e2e.test.ts

# Run tests matching pattern
npm test -- --testNamePattern="should handle"

# Update snapshots
npm test -- --updateSnapshot

# Clear Jest cache
npm test -- --clearCache

# Run with coverage
npm run test:coverage

# Watch mode
npm run test:watch
```

### Environment Verification
```bash
# Check Node version
node --version # Should be 18.x or 20.x

# Check npm version
npm --version

# Verify AWS credentials
aws sts get-caller-identity

# Test Jira connectivity
curl -u "$TEST_JIRA_EMAIL:$TEST_JIRA_TOKEN" \
  "$TEST_JIRA_URL/rest/api/3/myself"

# Test Bitbucket connectivity
curl -u "$TEST_BB_USERNAME:$TEST_BB_PASSWORD" \
  "https://api.bitbucket.org/2.0/user"
```
