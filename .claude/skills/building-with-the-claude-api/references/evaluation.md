# Evaluation

Systematic prompt evaluation: dataset generation, running evals, model-based grading, and code-based grading.

> See also: [additional-notes.md § S3](additional-notes.md#s3--evaluationmd--depth-additions) — why naive testing fails; rubric-design checklist; output-format instrumentation via prefill; result-shape narrative.

## Contents

- [Why evaluate](#why-evaluate)
- [The 5-step workflow](#the-5-step-workflow)
- [Generating test datasets](#generating-test-datasets)
- [Running the eval pipeline](#running-the-eval-pipeline)
- [Model-based grading](#model-based-grading)
- [Code-based grading](#code-based-grading)
- [Combining grader scores](#combining-grader-scores)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Why evaluate

Testing a prompt once, or tweaking it until "it looks good," is a trap. Real users produce inputs you won't anticipate. Evals give you an **objective metric** — a number that moves as you iterate — so you know whether a prompt change helped or hurt.

Three paths after drafting a prompt:
1. Ship after one test — risky.
2. Test a few times, tweak corners — still subjective.
3. Run through an eval pipeline, score, iterate — the only approach that scales.

## The 5-step workflow

1. **Draft a prompt.** Deliberately basic — this is your baseline.
2. **Create an eval dataset.** Inputs representing what users actually ask. Tens to thousands of records.
3. **Feed through Claude.** Each input merged with the prompt template, sent to the API.
4. **Feed through a grader.** Scores each output 1–10 (or pass/fail). Graders can be code, model, or human.
5. **Change prompt, repeat.** Compare average scores across versions.

## Generating test datasets

For code-generation / structured-task prompts, use Haiku (cheaper + faster) to synthesize the dataset. Ask for JSON, use prefill + stop_sequences, parse with `json.loads`.

```python
import json

def generate_dataset():
    prompt = """
Generate an evaluation dataset for a prompt evaluation. The dataset will be used
to evaluate prompts that generate Python, JSON, or Regex for AWS-related tasks.
Generate an array of JSON objects, each with a task and format field.

Example output:
```json
[
  {"task": "Description of task", "format": "python"}
]
```

* Focus on tasks solvable by a single function, JSON object, or regex
* Keep tasks small

Please generate 3 objects.
"""
    messages = []
    add_user_message(messages, prompt)
    add_assistant_message(messages, "```json")
    text = chat(messages, stop_sequences=["```"])
    return json.loads(text)

dataset = generate_dataset()
with open("dataset.json", "w") as f:
    json.dump(dataset, f, indent=2)
```

Keep the dataset small (2–5 cases) during prompt-iteration to keep the feedback loop tight. Expand to 20+ cases for final validation.

## Running the eval pipeline

Three functions, each with one job:

```python
def run_prompt(test_case):
    """Merges the prompt template with a test case, returns Claude's output."""
    prompt = f"Please solve the following task:\n\n{test_case['task']}"
    messages = []
    add_user_message(messages, prompt)
    return chat(messages)

def run_test_case(test_case):
    """Runs a single case and grades it."""
    output = run_prompt(test_case)
    model_grade = grade_by_model(test_case, output)
    syntax_score = grade_syntax(output, test_case)
    score = (model_grade["score"] + syntax_score) / 2
    return {
        "output": output,
        "test_case": test_case,
        "score": score,
        "reasoning": model_grade["reasoning"],
    }

def run_eval(dataset):
    """Runs the full dataset and reports the average."""
    from statistics import mean
    results = [run_test_case(tc) for tc in dataset]
    avg = mean(r["score"] for r in results)
    print(f"Average score: {avg}")
    return results
```

Load the dataset once and iterate:

```python
with open("dataset.json") as f:
    dataset = json.load(f)
results = run_eval(dataset)
```

Expect 30+ seconds per run even with Haiku. Parallelize with `asyncio` + `AsyncAnthropic` or a worker pool once you've scaled past a few cases. Start with a low concurrency (3–5) to avoid rate limits.

## Model-based grading

Use another Claude call to judge output quality. Ask for **strengths, weaknesses, reasoning, AND score** — not just a score. Without the structured breakdown, graders converge on a bland 6/10.

```python
def grade_by_model(test_case, output):
    eval_prompt = f"""
You are an expert code reviewer. Evaluate this AI-generated solution.

Task: {test_case['task']}
Solution: {output}

Provide your evaluation as a structured JSON object with:
- "strengths": An array of 1-3 key strengths
- "weaknesses": An array of 1-3 key areas for improvement
- "reasoning": A concise explanation of your assessment
- "score": A number between 1-10
"""
    messages = []
    add_user_message(messages, eval_prompt)
    add_assistant_message(messages, "```json")
    text = chat(messages, stop_sequences=["```"])
    return json.loads(text)
```

Best for subjective criteria: quality, completeness, instruction-following, tone, safety.

## Code-based grading

For objectively checkable properties (syntax validity, length bounds, required keywords, forbidden keywords), write deterministic code graders.

```python
import json, ast, re

def validate_json(text):
    try:
        json.loads(text.strip())
        return 10
    except json.JSONDecodeError:
        return 0

def validate_python(text):
    try:
        ast.parse(text.strip())
        return 10
    except SyntaxError:
        return 0

def validate_regex(text):
    try:
        re.compile(text.strip())
        return 10
    except re.error:
        return 0

def grade_syntax(output, test_case):
    fmt = test_case.get("format")
    if fmt == "json":
        return validate_json(output)
    if fmt == "python":
        return validate_python(output)
    if fmt == "regex":
        return validate_regex(output)
    return 0
```

Code graders are fast and free. Use them for every checkable property. Reserve model graders for the qualities code can't check.

## Combining grader scores

Simple average is the default:

```python
score = (model_score + syntax_score) / 2
```

Weight them if one matters more. For code generation, you might weight syntax heavier:

```python
score = 0.3 * model_score + 0.7 * syntax_score
```

## Gotchas

- **Model graders are noisy.** Run each case 2–3× and average if precision matters.
- **Bias by example.** If the grader prompt includes examples of good/bad output, those examples bias scoring — keep them representative.
- **Bad baselines are actually fine.** 2.3/10 on a first iteration is normal. What matters is whether the score moves when you change the prompt.
- **Dataset drift.** If you regenerate the dataset mid-iteration, score comparisons become meaningless. Freeze the dataset across iterations.
- **Don't optimize to the eval.** Overfitting to your eval dataset makes the prompt great at your test cases and brittle on real inputs. Hold out 20% as a validation set you only check at the end.
- **Rate limits.** Parallelizing 50+ concurrent eval calls will trigger 429s. Start at `max_concurrent=3–5` and increase cautiously.
- **Use Haiku for grading** when possible — it's fast, cheap, and good enough for scoring structured JSON outputs.

## Sources

Distilled from the prior 62-lesson curriculum (lessons 9–14). The source lessons have been retired; this reference is self-contained. See [additional-notes.md § S3](additional-notes.md#s3--evaluationmd--depth-additions) for depth addenda.
