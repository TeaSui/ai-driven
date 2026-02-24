# impl-12: Cost Controls (4.1)

**Status:** DONE
**Priority:** 4.1  
**Impact:** Prevent unexpected Claude API costs with billing alarms and budget limits

---

## Goal

- CloudWatch billing alarms for Claude API spend
- Monthly budget limits with automatic workflow disabling
- Per-ticket cost tracking (tokens used, API calls made)
- **Auto model/context fallback** based on context token size estimation

## Proposed Changes

### Per-Ticket Cost Tracking
- [ ] Add token usage fields to `TicketState` DynamoDB entity:
  | Field | Type | Purpose |
  |-------|------|---------|
  | `inputTokens` | Int | Claude input tokens |
  | `outputTokens` | Int | Claude output tokens |
  | `estimatedCostUsd` | Double | Estimated cost based on model pricing |
- [ ] Update `ClaudeInvokeHandler` to parse and store token usage from Claude response
- [ ] Calculate cost based on model pricing table

### Auto-Fallback & Token Thresholds
- [ ] Add `COST_AWARE_MODE` env var
- [ ] In `ClaudeInvokeHandler`, if cost aware mode is enabled, pre-calculate token length before invoking model
- [ ] If estimated input tokens > limits, automatically downgrade from `FULL_REPO` to `INCREMENTAL` or downgrade model (e.g. from Opus to Sonnet)
- [ ] Add warning to Jira comment when auto-fallback is triggered

### Monthly Budget Tracking
- [ ] Create `BudgetTracker` utility that sums costs from DynamoDB (GSI by month)
- [ ] Add `MONTHLY_BUDGET_USD` env var (default: `100`)
- [ ] Check budget before invoking Claude; skip if exceeded
- [ ] Log warning at 80% threshold

### CloudWatch Alarms
- [ ] Add custom metric: `AIDriven/MonthlyCostUSD`
- [ ] Publish cost metric after each invocation
- [ ] Create alarm: threshold at 90% of `MONTHLY_BUDGET_USD`
- [ ] SNS notification for budget warnings

### CDK Stack
- [ ] Add `MONTHLY_BUDGET_USD` to `lambdaEnvironment`
- [ ] Add CloudWatch alarm + SNS topic for cost alerts
- [ ] Add dashboard widget for cost tracking

## Testing Strategy

- [ ] Unit test cost calculation for each model
- [ ] Unit test budget check with mock DynamoDB data
- [ ] Unit test alarm triggers at threshold

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/cost/BudgetTracker.java` |
| NEW | `core/src/main/java/com/aidriven/core/cost/ModelPricing.java` |
| MODIFY | `core/src/main/java/com/aidriven/core/model/TicketState.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/ClaudeInvokeHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
