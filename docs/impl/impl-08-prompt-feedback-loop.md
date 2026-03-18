# impl-08: Prompt Iteration Feedback Loop (2.3)

**Status:** ✅ Complete  
**Priority:** 2.3  
**Impact:** Data-driven prompt improvement based on PR approval/rejection rates

---

## Goal

Track AI generation quality and use feedback to improve prompts:
1. Record PR approval/rejection rates per ticket type
2. A/B test prompt variations
3. Store successful prompts as templates

## Proposed Changes

### Quality Metrics Storage
- [x] Create `GenerationMetrics` DynamoDB entity with fields:
  | Field | Type | Purpose |
  |-------|------|---------|
  | `ticketKey` | String | Links to ticket |
  | `model` | String | Claude model used |
  | `promptVersion` | String | Identifies prompt template |
  | `tokensUsed` | Int | Input + output tokens |
  | `filesGenerated` | Int | Number of files in PR |
  | `prApproved` | Boolean | Set when PR is merged or rejected |
  | `timeToApproval` | Long | Seconds from PR creation to merge |
  | `ticketLabels` | List | For filtering by ticket type |
- [x] Add `GenerationMetricsRepository` for DynamoDB operations

### ClaudeInvokeHandler Updates
- [x] Record metrics after successful Claude invocation
- [x] Store prompt version identifier in metrics

### MergeWaitHandler Updates
- [x] On merge callback, update `prApproved = true` and `timeToApproval`
- [x] On PR close (not merged), update `prApproved = false`

### Prompt Templates (Phase 2)
- [x] Create `prompt-templates/` directory with versioned prompt files
- [x] Add `PROMPT_VERSION` env var to select active template
- [x] A/B testing: randomly assign tickets to prompt versions

### Reporting Dashboard
- [x] Add CloudWatch custom metrics for approval rates
- [x] Add dashboard widget for prompt version comparison

## Testing Strategy

- [x] Unit test metrics recording in ClaudeInvokeHandler
- [x] Unit test merge callback updating metrics
- [x] Integration test: full workflow records and updates metrics

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/model/GenerationMetrics.java` |
| NEW | `core/src/main/java/com/aidriven/core/repository/GenerationMetricsRepository.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/ClaudeInvokeHandler.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/MergeWaitHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
