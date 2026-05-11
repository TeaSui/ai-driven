---
description: Generate an optimal Claude prompt structure from a user description. Asks clarifying questions when details are missing, then outputs a minimal or full XML-structured prompt.
argument-hint: "[description of what you want the prompt to do]"
---

# /prompt-builder

## Objective

Turn a natural-language description of a task into a well-structured Claude prompt. Choose between **minimal** and **full** versions based on complexity. Ask for missing details before generating.

## Arguments

- `$ARGUMENTS` — free-text description of the prompt's purpose.
- If `$ARGUMENTS` is empty: ask the user "What do you want this prompt to do?" and wait.

## Process

### 1. Parse the input

Extract what you can from `$ARGUMENTS`:

| Field | What to look for |
|---|---|
| **Role** | Who the AI is ("act as a", "you are a", domain expert implied) |
| **Task** | The verb — summarize, classify, extract, rewrite, answer |
| **Inputs** | Documents, variables, user text the prompt will receive |
| **Output format** | JSON, markdown, free text, table, code |
| **Constraints** | Tone, length, rules, things to avoid |
| **Examples** | Any sample input/output pairs mentioned |

### 2. Decide: enough info?

**Enough info** means you can write a concrete `<role>`, a concrete `<task>`, and at least one `<constraints>` bullet. If any of these are completely missing or vague, ask.

**Ask ONE targeted question per gap** (not a list of 6). Pick the most important missing piece. After the user answers, re-evaluate — ask a second question only if a second gap remains. Stop asking after 3 rounds maximum; fill remaining gaps with sensible defaults and flag them.

Examples of targeted questions:
- "What should Claude output — JSON, markdown, or free text?"
- "Who is the role here — a customer support agent, a code reviewer, a data analyst?"
- "Will the prompt receive a long document, or just a short user query?"

### 3. Choose version

Use **Full** when ANY of these apply:
- Prompt will receive documents or long variable content (>~500 tokens)
- Task has multiple steps or edge cases to handle explicitly
- Output needs a concrete schema (JSON, table, structured format)
- 3+ examples are needed to teach the pattern
- Constraints are complex or counter-intuitive

Use **Minimal** otherwise (simple one-step tasks, short inputs, free-text output).

If the user explicitly requests a version, honor it.

### 4. Generate the prompt

#### Minimal version

```xml
<role>You are [role]. You [one-sentence mission].</role>

<instructions>
1. [Step 1]
2. [Step 2]
3. [Step 3]
</instructions>

<constraints>
- [Hard rule]
- [Output format rule]
</constraints>

<examples>
  <example>
    <sample_input>...</sample_input>
    <ideal_output>...</ideal_output>
  </example>
</examples>

<user_query>{{USER_INPUT}}</user_query>
```

#### Full version

```xml
<role>
[One or two sentences. Who Claude is and what it does.]
</role>

<task>
[One imperative sentence: what to produce this turn.]
</task>

<!-- Long variable content goes FIRST for cache efficiency -->
<documents>
  <document index="1">
    <source>filename-or-id</source>
    <document_content>
      {{VAR_1}}
    </document_content>
  </document>
</documents>

<instructions>
1. [Numbered, ordered, one action per step.]
2. [Edge-case handling explicitly here.]
3. [For long-doc tasks: "First, quote relevant passages in <quotes>, then answer in <answer>."]
</instructions>

<constraints>
- [Tell Claude what TO do, not what not to do.]
- [State scope explicitly.]
- [Failure behavior: "If you lack the information, respond with {"error": "insufficient_context"} — do not guess."]
</constraints>

<examples>
  <example>
    <sample_input>...</sample_input>
    <ideal_output>...</ideal_output>
    <why_good>[Specific reasons tied to constraints.]</why_good>
  </example>
</examples>

<output_format>
[Concrete schema. JSON → show the schema. Free text → "Start with a one-sentence verdict, then 2–3 bullet reasons."]
</output_format>

<user_query>
{{USER_INPUT}}
</user_query>
```

### 5. Output

Present:
1. Which version was chosen and why (one sentence).
2. The filled-in prompt, ready to copy.
3. A short list of **flagged defaults** — any fields you filled in with assumptions the user should review (max 3 bullets).

**Inline the user's input into `<user_query>`.** `$ARGUMENTS` is the concrete request the prompt will actually process — do NOT leave it as `{{USER_INPUT}}`. The placeholder only applies when the user asked you to design a reusable template (e.g., "give me a template I can reuse for different emails"). Default behavior: paste the verbatim `$ARGUMENTS` text into `<user_query>`.

If a field has no good default (e.g., you have no idea what the output schema should be), leave a `[TODO: ...]` placeholder and flag it.

## Decision guide

```
Has long documents / variable content?  ──YES──► Full
         │
         NO
         │
Multiple steps or edge cases?  ──YES──► Full
         │
         NO
         │
Structured output schema needed?  ──YES──► Full
         │
         NO
         │
         ▼
       Minimal
```

## Example run

**User:** `/prompt-builder I need a prompt that classifies customer support emails into categories`

**Output:**

> Choosing **Minimal** — single classification step, short input, free-text category output.
>
> ```xml
> <role>You are a customer support triage agent. You classify incoming support emails into the correct category.</role>
>
> <instructions>
> 1. Read the email in <user_query>.
> 2. Assign exactly one category from this list: Billing, Technical, Account Access, General Inquiry, Complaint.
> 3. If the email fits multiple categories, choose the primary issue.
> </instructions>
>
> <constraints>
> - Output the category name only — no explanation unless asked.
> - If the email is spam or irrelevant, output: Spam.
> </constraints>
>
> <examples>
>   <example>
>     <sample_input>Hi, I was charged twice this month for my subscription.</sample_input>
>     <ideal_output>Billing</ideal_output>
>   </example>
> </examples>
>
> <user_query>{{EMAIL_TEXT}}</user_query>
> ```
>
> **Flagged defaults:**
> - Category list is assumed — replace with your actual taxonomy.
> - Output is the category name only; change `<constraints>` if you need a confidence score or explanation.
