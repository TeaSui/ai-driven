# impl-22: Bedrock Large Context Optimization

## Status: 🚧 DESIGN (Phase 1 partially superseded by impl-18)

## Overview

This guide outlines optimizations to prevent AWS Lambda timeouts (typically 2–15 minutes) when processing large code
contexts (~500KB–1MB+) with Amazon Bedrock (Anthropic Claude models like Sonnet 4.6).

### Root Causes Identified

| Issue                                                         | Impact                                             |
|---------------------------------------------------------------|----------------------------------------------------|
| Full repository context downloads                             | Excessive input tokens                             |
| Synchronous blocking calls to `invokeModel()` or `converse()` | Long wait times                                    |
| No response streaming                                         | Full output buffered in memory                     |
| Lambda timeout mismatch                                       | CDK specifies 15 min, but effective limit is lower |

### Goals

- Reduce effective context size by 60–80%
- Enable real-time streaming to avoid full buffering
- Support async fallback for very long inferences
- Maintain high-quality responses without significant accuracy loss

---

## Solution Architecture

Three-phase layered approach (implement in order):

1. **Context Size Reduction** (Phase 1 – Immediate Impact)
2. **Response Streaming** (Phase 2 – Critical for Latency)
3. **Asynchronous Processing** (Phase 3 – For Extreme Cases)

```
┌─────────────────────────────────────────────────────────────┐
│                  OPTIMIZATION LAYERS                        │
├─────────────────────────────────────────────────────────────┤
│ Phase 1: Context Reduction                                  │
│   └─ File Summarization (>50KB threshold)                   │
│ Phase 2: Streaming Responses                                │
│   └─ ConverseStream / InvokeModelWithResponseStream         │
│ Phase 3: Async Fallback                                     │
│   └─ DynamoDB + SNS + Background Lambda                     │
└─────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Context Size Reduction

**Approach:** Intelligently summarize large files instead of sending full content.

> [!NOTE]
> **impl-18 already ships this for agent mode.** The `source_control_view_file_outline` tool extracts Java class/method
> signatures (via `JavaAstParser`) and returns `~8000` chars max, eliminating full file sends during the ReAct loop.
> Phase 1 (`FileSummarizer.java`) is only needed for the **`FULL_REPO` context mode** used by the non-agent pipeline.

### Implementation

| Component | Description                                                           |
|-----------|-----------------------------------------------------------------------|
| File      | `core/src/main/java/com/aidriven/core/util/FileSummarizer.java`       |
| Threshold | `SUMMARIZATION_THRESHOLD=50000` characters (configurable via env var) |
| Rollback  | Set threshold to `999999999` to disable                               |

### Supported Languages

| Language          | Extracted Elements                                                                                     |
|-------------------|--------------------------------------------------------------------------------------------------------|
| **Java**          | Package, imports, class/interface signatures, method signatures, fields, key annotations (regex-based) |
| **TypeScript/JS** | Classes, functions, exports, interfaces/types                                                          |
| **Python**        | Classes, function defs with docstring summaries                                                        |

### Integration

Modified `FullRepoStrategy.java` → call summarizer for files > threshold

### Benefits

- Reduces context size 60–80% on average
- Preserves structural info → model still understands architecture

### Configuration (Env Vars)

```bash
SUMMARIZATION_THRESHOLD=50000    # chars before summarization
```

### Risks & Mitigations

| Risk                                                 | Mitigation                                   |
|------------------------------------------------------|----------------------------------------------|
| Regex extraction misses important context            | Test thoroughly, allow threshold adjustment  |
| Model produces poor results on over-summarized files | Start with higher threshold, monitor quality |

---

## Phase 2: Response Streaming

**Approach:** Use streaming APIs to receive partial responses in real-time → avoid buffering entire output.

### Implementation

Switch from `invokeModel()` → `converseStream()` (preferred) or `invokeModelWithResponseStream()`

**Java SDK v2 example (in BedrockClient):**

```java
// AWS SDK v2: converseStream uses a visitor/subscriber pattern, not .stream().forEach()
ConverseStreamRequest request = ConverseStreamRequest.builder()
        .modelId("global.anthropic.claude-sonnet-4-6")  // Use inference profile
        .messages(messages)
        .system(systemPrompt)
        .inferenceConfig(InferenceConfiguration.builder()
                .maxTokens(32768)  // DEFAULT_MAX_TOKENS in BedrockClient
                .temperature(0.2f) // Match CLAUDE_TEMPERATURE default
                .build())
        .build();

// SDK v2 streaming: use the ResponseTransformer visitor approach
bedrockClient.converseStream(request, ConverseStreamResponseHandler.builder()
        .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                .onContentBlockDelta(chunk -> {
                    String text = chunk.delta().text();
                    if (text != null) {
                        // Process chunk: accumulate, stream to Jira, etc.
                        System.out.print(text);
                    }
                })
                .build())
        .build());
```

> **Note:** BedrockClient currently only supports `invokeModel()`. Streaming (`converseStream()` or `invokeModelWithResponseStream()`) needs to be implemented.

### Handling Partial Results

Update Jira comment progressively or stream back to frontend.

### Benefits

| Benefit                  | Description                       |
|--------------------------|-----------------------------------|
| No full memory buffer    | Reduces risk of OOM               |
| Real-time feedback       | Improves perceived latency        |
| Works with summarization | Complements Phase 1 optimizations |

### Risks & Mitigations

| Risk                   | Mitigation                                       |
|------------------------|--------------------------------------------------|
| Pricing differences    | Check AWS pricing for streaming vs non-streaming |
| Complex error handling | Implement retry logic for partial results        |
| Connection drops       | Save progress to handle resume                   |

---

## Phase 3: Asynchronous Processing (Optional Fallback)

**Approach:** For cases where even streaming times out (>10–15 min), offload to background.

### Components (via CDK)

| Component                       | Purpose                                            |
|---------------------------------|----------------------------------------------------|
| `AsyncResultsTable` (DynamoDB)  | Store ticket ID, status, final result              |
| `AsyncCompletionTopic` (SNS)    | Notify on completion                               |
| `AsyncClaudeProcessor` (Lambda) | Long-running streaming processor                   |
| Main Lambda                     | Trigger async → return "Processing..." immediately |

### Flow

1. Main Lambda detects large context → invoke async Lambda
2. Async Lambda streams Bedrock → saves to DynamoDB
3. SNS notifies → update Jira via comment

### CDK Placement

Add to `infrastructure/lib/ai-driven-stack.ts` after the existing `agentProcessorFn` block:
- `AsyncResultsTable` → alongside `stateTable` definition
- `AsyncCompletionTopic` → new `sns.Topic` construct
- `AsyncClaudeProcessor` → new `lambda.Function` with 15-min timeout

### Env Vars

```bash
ASYNC_MODE_ENABLED=false               # Opt-in
ASYNC_RESULTS_TABLE=ai-driven-async-results
```

### When to Enable

Only if Phase 1+2 still timeout after monitoring.

### Risks & Mitigations

| Risk                          | Mitigation                               |
|-------------------------------|------------------------------------------|
| Infrastructure complexity     | Only enable if Phase 1+2 insufficient    |
| Security for async processing | Use IAM roles, encrypt DynamoDB data     |
| Error recovery                | Implement dead-letter queue for failures |

---

## Testing Strategy

| Scenario                    | Expected Behavior                                |
|-----------------------------|--------------------------------------------------|
| Small Context (<50KB)       | No summarization, normal sync flow               |
| Large Context (>50KB)       | Summarization logs, streaming chunks, no timeout |
| Async Fallback (if enabled) | Large job triggers DynamoDB/SNS/Jira update      |

---

## Monitoring

```bash
aws logs tail /aws/lambda/ai-driven-claude-invoke --follow
aws logs filter-log-events --log-group-name /aws/lambda/... --filter-pattern "Received chunk"
```

---

## Success Metrics

| Metric            | Target                       |
|-------------------|------------------------------|
| Context reduction | >60%                         |
| Lambda duration   | <90–120 seconds (most cases) |
| Timeout errors    | 0 in CloudWatch              |
| PR creation       | Successful                   |

---

## Rollback Plan

| Action                | Command                             |
|-----------------------|-------------------------------------|
| Disable summarization | `SUMMARIZATION_THRESHOLD=999999999` |
| Disable async         | `ASYNC_MODE_ENABLED=false`          |
| Revert streaming      | Use original `invokeModel()`        |

---

## Future Enhancements

| Feature                   | Description                                                    |
|---------------------------|----------------------------------------------------------------|
| Prompt Caching            | Cache repo structure/system prompt → reduce token cost/latency |
| Embedding-Based Retrieval | Titan Embeddings + OpenSearch → semantic chunk retrieval       |
| Adaptive Thinking         | Use Claude's built-in compaction for long conversations        |
| Progress Callbacks        | Emit intermediate Jira updates during streaming                |

---

## References

| Resource                    | Link                                                                              |
|-----------------------------|-----------------------------------------------------------------------------------|
| AWS Bedrock Streaming       | https://docs.aws.amazon.com/bedrock/latest/userguide/inference-streaming.html     |
| Converse API                | https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html  |
| Anthropic Claude on Bedrock | https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-claude.html |
| Prompt Caching & Compaction | AWS Bedrock docs (2026 updates)                                                   |
