# Prompt Engineering

Iterative techniques for improving prompt quality: clarity, specificity, XML structure, and examples.

## Contents

- [The iterative loop](#the-iterative-loop)
- [Be clear and direct](#be-clear-and-direct)
- [Be specific](#be-specific)
- [Structure with XML tags](#structure-with-xml-tags)
- [One-shot and multi-shot examples](#one-shot-and-multi-shot-examples)
- [Gotchas](#gotchas)
- [Sources](#sources)

## The iterative loop

Prompt engineering is a measurement-driven cycle. Don't guess whether a change helps — score it.

1. Set a goal (what the prompt must accomplish).
2. Write an initial prompt (deliberately basic).
3. Evaluate against a dataset (see [evaluation.md](evaluation.md)).
4. Apply one technique (clarity → specificity → XML → examples).
5. Re-evaluate; keep the version with the higher score.

Make **one change at a time** so score deltas are attributable. Starting scores of ~2/10 are normal; each technique typically adds 1–3 points. Scores in the curriculum progressed 2.32 → 3.92 (clarity) → 7.86 (specificity) as guidelines were added.

## Be clear and direct

The first line sets the stage. Make it an **instruction with a direct action verb**, not a question or rambling context.

Weak:
```
I was reading about renewable energy and geothermal energy sounds neat. What countries use it?
```

Strong:
```
Identify three countries that use geothermal energy. Include generation stats for each.
```

Rules:
- Lead with a verb: `Write`, `Generate`, `Identify`, `Extract`, `Classify`, `Summarize`.
- State the task in one sentence before any context.
- Drop conversational padding ("I was wondering if…").

## Be specific

Vague prompts produce vague outputs. Add two kinds of specificity:

### Output guidelines (almost always)

List the qualities the output must have — length, format, required elements, tone, style.

```
Generate a one-day meal plan for an athlete that meets their dietary restrictions.

Guidelines:
1. Include accurate daily calorie amount
2. Show protein, fat, and carb amounts
3. Specify when to eat each meal
4. Use only foods that fit restrictions
5. List all portion sizes in grams
6. Keep budget-friendly if mentioned
```

### Process steps (for complex reasoning)

When the task requires systematic thinking, list the reasoning steps Claude should walk through before producing the final answer.

```
Before answering, work through these steps:
1. Brainstorm three talents that would create dramatic tension
2. Pick the most interesting talent
3. Outline the pivotal scene that reveals it
4. Identify supporting characters that increase impact
Then write the story.
```

Use process steps for troubleshooting, decision-making, multi-factor analysis. Use output guidelines for almost every prompt.

## Structure with XML tags

When interpolating large or mixed content (data + instructions, code + docs, multiple variables), XML tags disambiguate boundaries.

```
<athlete_information>
- Height: 6'2"
- Weight: 180 lbs
- Goal: Build muscle
- Dietary restrictions: Vegetarian
</athlete_information>

Generate a meal plan based on the athlete information above.
```

Rules:
- Use **descriptive** tag names — `<sales_records>` beats `<data>`, `<my_code>` + `<docs>` beats mixing them.
- Tags are a convention, not a parser — Claude recognizes them as delimiters but they don't enforce schema.
- Useful especially when any block is >~200 chars or when you have 2+ distinct content regions.
- Pair well with `system` prompts: put tag-structured data in the user message, put "how to process it" in `system`.

## One-shot and multi-shot examples

Showing beats telling. Provide input/output pairs that demonstrate the expected behavior — especially for edge cases (sarcasm, ambiguous formats, tricky categorizations).

```
<sample_input>
Great game tonight!
</sample_input>
<ideal_output>
Positive
</ideal_output>

<sample_input>
Oh yeah, I really needed a flight delay tonight! Excellent!
</sample_input>
<ideal_output>
Negative
</ideal_output>

Note: treat sarcasm carefully — surface tone may be positive but meaning negative.

Now classify:
<input>{tweet}</input>
```

Guidance:
- **One-shot**: one example; good for establishing a simple pattern.
- **Multi-shot**: 2–5 examples covering the variety of real inputs; use for edge cases and format-sensitive tasks.
- Wrap examples in consistent XML tags (`<sample_input>`, `<ideal_output>`).
- Explain *why* an output is ideal when the quality criteria aren't obvious from the example alone.
- **Mine examples from eval runs.** The highest-scoring outputs from a prior eval are gold — paste them back as few-shot examples.

## Gotchas

- **One change per iteration.** Stacking techniques without measurement hides which one helped.
- **Examples dominate instructions.** If your examples contradict your instructions, Claude usually follows the examples.
- **Too-specific examples narrow the output.** Three examples all of length 50 words will bias responses to that length even if you want variety.
- **XML tags are a hint, not a schema.** Don't rely on them for validation — validate Claude's output separately.
- **Don't over-engineer early.** Start basic, measure, add one technique, measure again. The curriculum shows dramatic gains from just directness + guidelines before any examples or XML.

## Sources

Distilled from the prior 62-lesson curriculum (lessons 15–19). The source lessons have been retired; this reference is self-contained.
