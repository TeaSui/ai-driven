# impl-01: Configuration Externalization

**Status:** ✅ Complete  
**Priority:** 1.1  
**Impact:** Low risk, immediate value — allows tuning without redeployment

---

## Changes

- [x] `BitbucketFetchHandler` — externalize 3 constants to env vars
- [x] `ClaudeInvokeHandler` — externalize context limit + model name to env vars
- [x] `ClaudeInvokeHandler` — externalize max tokens + temperature to env vars
- [x] `ClaudeClient` — accept `maxTokens` + `temperature` as constructor params
- [x] `ai-driven-stack.ts` — add all 8 new env vars to shared Lambda config
- [x] `ai-driven-stack.ts` — externalize merge wait timeout

## Configuration Table

| Env Var | Handler / File | Default | Purpose |
|---------|----------------|---------|---------|
| `MAX_FILE_SIZE_CHARS` | BitbucketFetchHandler | `100000` | Max chars per source file |
| `MAX_TOTAL_CONTEXT_CHARS` | BitbucketFetchHandler | `3000000` | Total context cap (~3MB) |
| `MAX_FILE_SIZE_BYTES` | BitbucketFetchHandler | `500000` | Skip files > 500KB on disk |
| `MAX_CONTEXT_FOR_CLAUDE` | ClaudeInvokeHandler | `700000` | Max chars sent to Claude |
| `CLAUDE_MODEL` | ClaudeInvokeHandler | `claude-opus-4-6` | Claude model identifier |
| `CLAUDE_MAX_TOKENS` | ClaudeInvokeHandler | `32768` | Max output tokens per request |
| `CLAUDE_TEMPERATURE` | ClaudeInvokeHandler | `0.2` | Model temperature (creativity) |
| `MERGE_WAIT_TIMEOUT_DAYS` | CDK stack | `7` | Merge wait task timeout |

## Files Modified

- `application/lambda-handlers/src/main/java/com/aidriven/lambda/BitbucketFetchHandler.java`
- `application/lambda-handlers/src/main/java/com/aidriven/lambda/ClaudeInvokeHandler.java`
- `application/claude-client/src/main/java/com/aidriven/claude/ClaudeClient.java`
- `infrastructure/lib/ai-driven-stack.ts`
