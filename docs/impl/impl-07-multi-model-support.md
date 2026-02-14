# impl-07: Multi-Model Support (2.2)

**Status:** ✅ Complete  
**Priority:** 2.2  
**Impact:** Choose Claude model per ticket for cost/speed optimization

---

## Goal

Allow selecting the Claude model per ticket via Jira labels:
- `ai-model:haiku` → Fast/cheap for small formatting or doc changes
- `ai-model:sonnet` → Default balanced option
- `ai-model:opus` → Complex architectural changes

## Proposed Changes

### Model Resolution
- [x] Add `resolveModel(List<String> labels)` method to `TicketInfo` or new `ModelSelector` utility
- [x] Map label values to Anthropic model IDs:
  | Label | Model ID |
  |-------|----------|
  | `ai-model:haiku` | `claude-haiku-4-5` |
  | `ai-model:sonnet` | `claude-sonnet-4-5` |
  | `ai-model:opus` | `claude-opus-4-6` |
- [x] Default to `CLAUDE_MODEL` env var if no label matches

### FetchTicketHandler
- [x] Include resolved model in Step Functions output map

### ClaudeInvokeHandler
- [x] Read `model` from Step Functions input (if present)
- [x] Override `claudeModel` field with per-ticket model when provided
- [x] Log which model is being used for observability

### ClaudeClient
- [x] Already supports model as constructor param — no changes needed
- [x] May need per-request model override (new `chat()` overload)

### CDK Stack
- [x] No changes needed (model is resolved at runtime from labels)

## Testing Strategy

- [x] Unit test `ModelSelector` with various label combinations
- [x] Unit test fallback to default model when no label matches
- [x] End-to-end: ticket with `ai-model:haiku` label uses Haiku

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/model/ModelSelector.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/FetchTicketHandler.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/ClaudeInvokeHandler.java` |
