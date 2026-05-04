# Agents & Workflows

When to use a fixed multi-step **workflow** vs. an open-ended **agent**, and the four workflow patterns that cover most real systems: parallelization, chaining, routing, and evaluator-optimizer. Plus the two things agents need to be useful: composable tools and the ability to inspect their environment.

## Contents

- [Workflow vs agent — pick first](#workflow-vs-agent--pick-first)
- [Parallelization](#parallelization)
- [Chaining](#chaining)
- [Routing](#routing)
- [Evaluator-Optimizer](#evaluator-optimizer)
- [Agents: composable tools](#agents-composable-tools)
- [Agents: environment inspection](#agents-environment-inspection)
- [Decision rubric](#decision-rubric)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Workflow vs agent — pick first

| | Workflow | Agent |
|---|---|---|
| Sequence | Predefined steps you author | Claude decides next step |
| Inputs | Known shape | Open-ended user goal |
| Reliability | High, testable per step | Lower, harder to test |
| Flexibility | Constrained to known task types | Handles novel situations |
| Use when | The task type is known and stable | The space of requests is too wide to enumerate |

**Default to workflows.** Agents are for the slice of problems where you genuinely cannot enumerate the steps — interactive assistants, IDE-style tools, novel tool composition. If you can write down "first do A, then B, then C," it should be a workflow.

## Parallelization

Split one decision into independent sub-evaluations, run them in parallel, then aggregate.

```python
import asyncio

async def assess(material: str, spec: str) -> dict:
    return await call_claude(
        system=f"You are a {material} suitability expert.",
        user=f"Spec:\n{spec}\nReturn JSON: {{score, rationale}}",
    )

async def material_review(spec: str) -> dict:
    metal, polymer, ceramic = await asyncio.gather(
        assess("metal", spec),
        assess("polymer", spec),
        assess("ceramic", spec),
    )
    # Final aggregation call (or pure code)
    return await aggregate({"metal": metal, "polymer": polymer, "ceramic": ceramic})
```

Use when: the decision decomposes cleanly into independent dimensions and each dimension benefits from a focused prompt.

## Chaining

Sequential subtasks where each step's output feeds the next. Solves the "long prompt problem" — when one mega-prompt has so many constraints that Claude drops some.

```python
async def write_article(topic: str) -> str:
    draft       = await call_claude(system=DRAFT_SYS, user=topic)
    fact_check  = await call_claude(system=FACT_SYS,  user=draft)
    rewrite     = await call_claude(system=REWRITE_SYS,
                                    user=f"Draft:\n{draft}\nIssues:\n{fact_check}")
    return rewrite
```

Use when: a task has multiple constraints that interfere with each other in one prompt, or you want non-LLM processing (validators, retrieval, calculators) between steps.

## Routing

Classify the input, then dispatch to a specialised pipeline. The router is itself a Claude call returning a category label.

```python
async def handle(query: str) -> str:
    category = await call_claude(
        system="Classify into one of: programming, surfing, finance, other.",
        user=query,
    )
    pipelines = {
        "programming": programming_pipeline,
        "surfing":     surfing_pipeline,
        "finance":     finance_pipeline,
        "other":       fallback_pipeline,
    }
    return await pipelines.get(category, fallback_pipeline)(query)
```

Use when: incoming requests cluster into distinct shapes that each benefit from a different prompt template. Cheaper than a single mega-prompt that has to handle every category.

## Evaluator-Optimizer

Producer generates output, grader evaluates it, feedback loops back until the grader accepts (or budget is exhausted). This is a workflow, not an agent — the steps are fixed; only the loop count varies.

```python
async def produce_until_good(spec: str, max_rounds: int = 3) -> str:
    feedback = ""
    for _ in range(max_rounds):
        draft  = await call_claude(system=PRODUCE_SYS,
                                   user=f"Spec: {spec}\nFeedback so far: {feedback}")
        verdict = await call_claude(system=GRADE_SYS, user=draft)
        if verdict["accepted"]:
            return draft
        feedback = verdict["feedback"]
    return draft  # last attempt; flag for human review
```

Bound the loop count. The grader can converge on "almost good" indefinitely.

## Agents: composable tools

If you must build an agent, the lever that makes it work is **tool design**. Composable primitives beat hyper-specialized tools.

- **Bad:** `book_flight_economy_round_trip_with_seat_choice(...)` — narrow, brittle, doesn't compose.
- **Good:** `search_flights`, `book_flight`, `change_seat` — Claude combines them however the user asks.

Claude Code's tool surface (`bash`, `read`, `write`, `edit`, `glob`, `grep`) is the canonical example: six tools that handle a near-infinite set of dev tasks because they compose.

Heuristic: if you find yourself writing a tool whose name encodes a specific workflow ("validate_and_send_then_retry…"), split it. Let Claude orchestrate.

## Agents: environment inspection

Agents act on systems they can't see. Without observation, they hallucinate the world's state. Every action-taking tool should be paired with an observation tool, and the system prompt should remind Claude to observe.

| Action | Pair with |
|---|---|
| `click(selector)` / `type(text)` | `screenshot()` |
| `write(path, content)` | `read(path)` first |
| `run(cmd)` | capture stdout/stderr |
| `apply_patch(diff)` | re-read file, run tests |

System-prompt nudges: "After each action, verify the result before continuing. If the screen does not show the expected state, stop and report."

This is the difference between a 30%-success agent and an 85%-success one.

## Decision rubric

Ask in order; first **yes** wins:

1. Can I write down the steps? → **Workflow.**
2. Are the steps independent? → Parallelization.
3. Does each step's output feed the next? → Chaining.
4. Do the inputs cluster into shapes? → Routing.
5. Need iterative quality gating? → Evaluator-Optimizer.
6. None of the above and the request space is open? → **Agent.** Now design composable tools and an inspection loop.

## Gotchas

- **Agents fail silently when they can't observe.** A "successful" tool call with no verification step is a coin flip on whether the world actually changed. Always pair action with observation.
- **Workflow patterns compose.** Real systems use chaining-of-routers, or parallelization-inside-chaining. The four patterns are building blocks, not exclusive choices.
- **Evaluator-Optimizer needs a loop bound.** Without `max_rounds`, a strict grader can chew through tokens forever.
- **Routers need a fallback category.** Misclassification is inevitable; design "other" or "unknown" as a first-class branch.
- **Agent freedom is expensive.** Each turn is a full API call with the running history. Workflows are 3–5 calls; agents can hit 30+ on a single user request. Cache aggressively (see `advanced-features.md`).
- **"Agent" is overused as a marketing term.** Most production "agents" are actually chains-with-routing. Call it what it is — workflows are easier to debug and ship.
- **Tool over-specialisation kills agents.** If your agent has 40 tools, most of which are workflows in disguise, you've built a workflow and lost. Cut to fundamentals.

## Sources

Distilled from the prior 62-lesson curriculum (lessons 56–62). The source lessons have been retired; this reference is self-contained.
