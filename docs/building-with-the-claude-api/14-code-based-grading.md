[↑ Curriculum index](README.md) · [← prev](13-model-based-grading.md) · [next →](15-prompt-engineering.md)

---

#### Summary

When evaluating AI models that generate code, you need more than just checking if the response makes sense. You also need to verify that the generated code actually has valid syntax and follows the correct format. This is where code-based grading comes in.

## How Code Grading Works

Code grading validates two key aspects of AI-generated responses:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623444%2F04_-_006_-_Code_Based_Grading_00.1748623444116.png)

- **Format** - The response should return only the requested code type (Python, JSON, or Regex) without explanations
- **Valid Syntax** - The generated code should actually parse correctly as the intended language
- **Task Following** - The response should directly address what was asked and be accurate

The first two criteria are handled by the code grader, while task following is evaluated by the model grader. Together, they provide a comprehensive evaluation.

## Syntax Validation Functions

To check if generated code has valid syntax, you can create three helper functions that attempt to parse the output:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623445%2F04_-_006_-_Code_Based_Grading_02.1748623445106.png)

```
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
```

Each function tries to parse the text as its respective format. If parsing succeeds, it returns a perfect score of 10. If it fails with an error, the syntax is invalid and returns 0.

## Dataset Format Requirements

For the code grader to know which validator to use, your test cases need to specify the expected output format:

```
{
    "task": "Create a Python function to validate an AWS IAM username",
    "format": "python"
}
```

You can update your dataset generation prompt to automatically include this format field by adding it to the example output structure.

## Improving Prompt Clarity

To get better results from your AI model, make your prompt instructions more specific about the expected output format:

```
* Respond only with Python, JSON, or a plain Regex
* Do not add any comments or commentary or explanation
```

You can also use a pre-filled assistant message with code blocks to encourage the model to return just the raw code:

```
add_assistant_message(messages, "```code")
```

This tells Claude to start generating code content without having to specify whether it's Python, JSON, or Regex ahead of time.

## Combining Scores

The final step is merging the model grader score with the code grader score. A simple approach is to take the average:

```
model_grade = grade_by_model(test_case, output)
model_score = model_grade["score"]
syntax_score = grade_syntax(output, test_case)

score = (model_score + syntax_score) / 2
```

This gives equal weight to both content quality and technical correctness. You might adjust these weights based on what matters more for your specific use case.

## Testing Your Implementation

Once you've implemented code grading, run your evaluation to get a baseline score. The score itself isn't inherently good or bad - what matters is whether you can improve it by refining your prompts. This gives you a quantitative way to measure prompt engineering progress rather than relying on subjective assessment.

#### Downloads

- [001_prompt_evals_fns.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762977673/001_prompt_evals_fns.ipynb?response-content-disposition=attachment&Expires=1776652459&Signature=HcuO90TEtUa-RRiQi3icg8jrWPUUA1R3sxlpWhgi~XjBtdUXWQI3~uQDcAowmatGYs3ehCwtYGZ0OLfkqWoJ-qW58nmrl3MNsU7RO8Ye-h-Ol0ObxM7dDNofT1sfkAQ1FvvBli~D24setnKaujb8tUGGaOZSnotdr6EwlWRcmSCGzOLZ2UYlIJvYRId2cWaB3AI2R-cOEDunTdH48JuznMVEVuVR~xrTrT7akDBagNKtC75au2TukJ7grL-6KqljqM4uhzCtNtMAf1UOBMCIc~yBHDrMW6pV29rPwHf9smU5YpmtesNq1hIxSEvdLcPYy4KFDoFSwL8RgbRyns35og__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
