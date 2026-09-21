# Concern Report

A risk-and-compatibility analysis common on larger backend teams. It compares *what exists* against *what's needed* and surfaces mismatches before they reach production. Used to compare contracts, detect downstream impact, assess backward compatibility, and gauge rollout risk.

Unlike the other documents, a Concern Report is mostly a **table** — each row is a concern with a status and the detail behind it. The value is the at-a-glance status column: a reviewer sees the MISMATCH/UNKNOWN rows immediately.

## Structure

```
# Concern Report: <change being assessed>

Date: <YYYY-MM-DD>
Scope: <what changed and what it touches>

## Summary
<1–2 lines: overall risk, and the single biggest concern>

## Concerns

| Concern | Status | Detail |
|---------|--------|--------|
| ...     | OK / MISMATCH / UNKNOWN | ... |

## Recommended actions
<ordered list, riskiest first; tie each to a concern row>
```

## Status values

- **OK** — verified consistent. Say *how* it was verified in Detail, don't just assert it.
- **MISMATCH** — a concrete gap. Detail must name the gap precisely (which field, which contract, which consumer).
- **UNKNOWN** — not yet verified. This is honest and useful — it tells the reader what still needs review. Never mark something OK to make the table look green.

## Worked example

```
# Concern Report: add `email` to kyc.document.processed event

Date: 2026-06-16
Scope: event schema change, 2 downstream consumers, 1 DMN ruleset

## Summary
Medium risk. One downstream consumer is missing the new field; DMN impact
is unverified.

## Concerns

| Concern                | Status   | Detail                                              |
|------------------------|----------|-----------------------------------------------------|
| Contract vs. storage   | OK       | All event fields map to kyc_documents columns        |
| Downstream impact      | MISMATCH | notification-service consumer does not read `email`  |
| DMN impact             | UNKNOWN  | Decision ruleset not yet reviewed for new field      |
| Idempotency            | OK       | Inbox pattern keyed on eventId, verified in tests    |
| Backward compatibility | OK       | Additive field; last 3 schema versions still parse   |

## Recommended actions
1. Confirm notification-service ignores unknown fields, or update it (MISMATCH).
2. Review DMN ruleset for `email` usage before rollout (UNKNOWN).
```

A Concern Report is only as good as its honesty: an UNKNOWN you surfaced is far more valuable than an OK you guessed at.
