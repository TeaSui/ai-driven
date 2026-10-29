# AI Integration Rules

## Model Selection
- Current selection policy: `~/.claude/references/model-guide.md`; current API details: `~/.claude/skills/building-with-the-claude-api/references/current-models.md`.
- Start an unspecified substantial workload with Opus 5.5 at medium effort. Use Sonnet 5.5 for clear bounded implementation, Haiku 5.5 for measured mechanical/volume tasks, Fable 5.1 for difficult reasoning and long autonomous work.
- Validate quality, latency and cost on representative cases; keep a concise rationale where a choice is non-obvious. Do not impose historical model/effort defaults on current tasks.

## Prompt Engineering Standards
- Store prompts as versioned constants, not inline strings
- Use XML tags for structured input sections (`<context>`, `<instructions>`, `<examples>`)
- Include few-shot examples for complex output formats
- Separate system prompt (behavioral instructions) from user prompt (task data)
- Prompts must be testable — each prompt needs a golden test set

## Prompt Versioning
```typescript
// WRONG
const prompt = `Analyze this transaction: ${data}`;

// RIGHT
const TRANSACTION_ANALYSIS_PROMPT_V2 = `
<instructions>
Analyze the financial transaction and classify it.
Return JSON with fields: category, risk_level, confidence.
</instructions>
<examples>...</examples>
<transaction>{{TRANSACTION_DATA}}</transaction>
`;
```

## Cost Guardrails
- Set `max_tokens` explicitly on every API call — never rely on model default
- Implement per-user and per-service token budgets
- Log token usage with every request (input tokens, output tokens, cost estimate)
- Alert when daily cost exceeds threshold ($10 dev, $50 staging, $200 prod)
- Use prompt caching for repeated context (system prompts, documents) — up to 90% cost reduction
- Batch requests where latency allows (Anthropic Batch API)

## Context Management
- Context window is finite — design for it
- For RAG: retrieve only what's needed, rank by relevance, limit to top-k chunks
- For long documents: chunk and summarize rather than stuffing full content
- For agents: keep prior turns/system/tools stable under preserved thinking; use documented compaction/edit controls for long sessions
- For PII handling with LLMs: see `data-privacy-patterns.md` (LLM/AI Usage with PII section)

## Adaptive Reasoning
- Current models use adaptive reasoning. Control depth with documented effort; fixed thinking budgets are rejected on the current family.
- Fable 5.1/Opus 5.5 cannot turn thinking off. Use lower effort for mechanical work where quality holds, instead of adding prose that asks the model not to think.
- Keep request output budgets adequate for thinking plus response. Re-baseline quality/cost when changing models rather than copying prior-generation tuning.

## Prompt Caching
- Cache system prompts that are ≥1024 tokens and used repeatedly
- Cache retrieved documents in RAG pipelines
- Use `cache_control: {"type": "ephemeral"}` on cacheable blocks
- Monitor cache hit rate — target >80% for repeated prompts

## Evaluation Requirements
- Every AI feature must have a golden dataset of ≥20 input/output pairs
- Define quality metrics before implementation (accuracy, F1, BLEU, human eval)
- Run evals in CI on every prompt change
- Minimum passing thresholds: accuracy ≥85%, no regression vs baseline
- Use promptfoo or custom eval harness — no vibes-based quality assessment

## Security Rules
- Sanitize all user input before embedding in prompts
- Never execute LLM output directly — parse and validate first
- Use structured outputs (JSON mode, tool use) for action-bearing responses
- Implement output validation: type checking, range checking, business rule validation
- Log all LLM interactions for audit (but redact PII before logging)
- Rate limit LLM endpoints per user: 60 requests/minute default

## Error Handling
```typescript
// Required error handling pattern
try {
  const response = await anthropic.messages.create({ ... });
  return parseAndValidate(response);
} catch (error) {
  if (error instanceof Anthropic.RateLimitError) {
    // Exponential backoff with jitter
    await delay(calculateBackoff(attempt));
    return retry(attempt + 1);
  }
  if (error instanceof Anthropic.APIError && error.status >= 500) {
    // Service error — fail gracefully
    logger.error('LLM service error', { error, correlationId });
    return fallback();
  }
  throw error; // Unknown errors bubble up
}
```

## Agent Design
- Define clear tool interfaces — ambiguous tool descriptions cause errors
- Implement max iteration limits (default: 10 turns)
- Implement token budget limits per agent run
- Use structured tool outputs (JSON) — never free-form text for actions
- Log tool names, status, latency and usage; redact sensitive arguments/results before diagnostic capture
- Design for partial failure — agents must handle tool errors gracefully

## Observability
- Trace every LLM call with correlation ID linking to user request
- Emit metrics: latency P50/P95/P99, token counts, cost, cache hit rate, error rate
- Use the project observability stack in `observability-patterns.md`; introduce another vendor only within the approved architecture/security scope
- Alert on: error rate >5%, latency P99 >10s, daily cost >threshold
