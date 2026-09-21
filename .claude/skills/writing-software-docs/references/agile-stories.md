# User Story + Acceptance Criteria

The most common requirement format in Agile. A story names *who*, *what*, and *why*; the acceptance criteria make "done" observable so QA can test it and a developer knows when to stop.

Note: in this repo's workflow, user stories are the `business-analyst-subagent`'s canonical artifact and persist to `docs/requirements/`. Use this structure whether you're the BA agent or the main session.

## Story structure

```
As a <role>
I want <capability>
So that <benefit>
```

The "so that" is not decoration — it's the why that lets implementers make sensible trade-offs when the story underspecifies a detail. A story without a real benefit is usually a task in disguise.

## Acceptance Criteria — Given/When/Then (Gherkin)

```
Given <initial context>
When <action>
Then <observable outcome>
And <additional outcome>
```

Each criterion must be observable from outside — something QA can check without reading the code. "Works correctly" is not a criterion; "the customer advances to the next step" is.

## Worked example

```
## Story
As a customer
I want to upload my ID card
So that I can continue the KYC process

## Acceptance Criteria

AC-1: Happy path
  Given the customer is on the KYC page
  When the customer uploads a valid ID (JPG/PNG/PDF, ≤ 10MB)
  Then the OCR service is called
  And the customer proceeds to the next step

AC-2: Unsupported format
  Given the customer is on the KYC page
  When the customer uploads a .docx file
  Then the upload is rejected
  And the accepted formats are shown

AC-3: OCR timeout
  Given a valid ID has been uploaded
  When the OCR service times out
  Then "Please try again" is shown
  And the uploaded file is preserved
```

## Quality checks specific to stories

- One story = one coherent capability. If "and" appears in the *want* clause, consider splitting.
- Cover the unhappy paths in AC, not just the happy path — that's where most defects hide.
- Link each AC to a requirement id when the story derives from a BRD/PRD/FRD (e.g. "AC-1 satisfies FR-1").
- Mark unresolved detail with `[NEEDS INPUT: …]` rather than inventing behavior.
