# Feature 6: Post-Task Evaluation

## Purpose

Automatically grade every completed agent task for quality, providing consistent feedback on Claude's output. Uses a separate grading model (`claude-sonnet-4-6 (fast slot, pinned to Sonnet)`) to assess correctness, completeness, code quality, and security.

## When It Activates

- `AI_DRIVEN_EVALUATION_ENABLED=true` in config
- Triggered automatically via `asyncio.create_task()` after any agent completes successfully
- Fire-and-forget: evaluation failures never block the main agent flow

## Grading Dimensions

| Dimension | Weight | What It Measures |
|-----------|--------|-----------------|
| Correctness | 35% | Does the output address the task requirements accurately? |
| Completeness | 25% | Are all aspects of the request covered? Nothing missing? |
| Code Quality | 25% | Clean code, follows patterns, maintainable, well-structured |
| Security | 15% | No vulnerabilities introduced, follows security best practices |

## Scoring

- Each dimension scored 1-10 by the grader model
- Combined score: weighted average across all dimensions
- Grader provides `reasoning`, `strengths[]`, and `weaknesses[]` for each dimension
- Structured JSON output enforced via prompt engineering

## Architecture

```
Agent completes task
        │
        ▼ (fire-and-forget)
┌─────────────────────────────┐
│  run_post_task_evaluation() │
│                             │
│  1. Check eval enabled      │
│  2. Build grading prompt    │
│     (task input + output)   │
│  3. Call claude-sonnet-4-6 (fast slot, pinned to Sonnet) │
│  4. Parse JSON scores       │
│  5. Store in DB             │
└─────────────────────────────┘
```

## Components

| File | Purpose |
|------|---------|
| `backend/src/ai_driven/evaluation/models.py` | `TaskEvaluation` SQLAlchemy model |
| `backend/src/ai_driven/evaluation/grader.py` | `TaskGrader` — builds prompt, calls Haiku, parses structured JSON |
| `backend/src/ai_driven/evaluation/runner.py` | `EvaluationRunner` + `run_post_task_evaluation()` entry point |
| `backend/src/ai_driven/agents/base.py` | Fire-and-forget call in `BaseAgent.run()` after completion |

## Database Schema

```sql
CREATE TABLE task_evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tasks(id),
    dimension VARCHAR(50) NOT NULL,
    score INTEGER CHECK (score BETWEEN 1 AND 10),
    reasoning TEXT,
    strengths TEXT[],
    weaknesses TEXT[],
    grader_model VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

## Design Decisions

- **Haiku for grading**: Fast and cheap — evaluation shouldn't cost more than the task itself
- **Fire-and-forget**: Evaluation runs in background, never blocks or fails the main task
- **Structured output**: Grader prompt asks for strengths, weaknesses, AND reasoning — otherwise models converge on 6/10 regardless of quality
- **Per-dimension storage**: Each dimension is a separate row, enabling aggregation queries like "average security score this week"

## Configuration

| Variable | Default | Notes |
|----------|---------|-------|
| `AI_DRIVEN_EVALUATION_ENABLED` | `false` | Master switch |
