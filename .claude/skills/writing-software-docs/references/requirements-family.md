# Requirements Family: BRD, PRD, FRD

These three sit on a spectrum from business intent → product behavior → exact functional detail. Pick by who's asking and how much detail they need.

- **BRD** — "What does the business want?" (BA/BO/PM). Highest level.
- **PRD** — "Why does this product feature exist and how does it work?" (PM/PO). Adds personas, flows, metrics.
- **FRD** — "Exactly how does this function behave?" (BA/Dev). Inputs, validation, exceptions, acceptance.

When unsure between PRD and FRD: if the reader needs to make a product/scope decision, it's a PRD; if they need to build the exact behavior, it's an FRD.

---

## BRD — Business Requirements Document

```
# BRD: <title>

## 1. Introduction
### 1.1 Purpose
### 1.2 Scope
### 1.3 Definitions  <!-- if docs/domain/glossary.md exists, point to it and list only doc-local terms; do not restate glossary entries (rules/single-source-of-truth.md) -->
## 2. Business Background
## 3. Business Objectives
## 4. Stakeholders
## 5. Functional Requirements
## 6. Non-functional Requirements
## 7. Assumptions
## 8. Constraints
## 9. Risks
## 10. Success Metrics
```

**Worked example (excerpts):**

```
## 3. Business Objectives
BO-1: Reduce KYC completion time from current baseline to target. [NEEDS INPUT: baseline and target]
BO-2: Increase first-attempt KYC pass rate. [NEEDS INPUT: current rate and target]

## 5. Functional Requirements
FR-1: The customer can upload an ID card.
       Supported formats: JPG, PNG, PDF. Max size: 10MB.
FR-2: The system extracts ID fields via OCR. [NEEDS INPUT: provider]

## 6. Non-functional Requirements
NFR-1: Upload-to-OCR response time target. [NEEDS INPUT: p95 latency target]
NFR-2: Availability 99.9%. [NEEDS INPUT: stakeholder hasn't set a target]

## 10. Success Metrics
- KYC median completion time. [NEEDS INPUT: target]
- First-attempt pass rate. [NEEDS INPUT: target]
```

---

## PRD — Product Requirements Document

The most common doc at product companies (Google, Meta, Airbnb, Uber). Answers: why does this exist, how does it work, how do we measure success.

```
# PRD: <title>

## 1. Overview
## 2. Problem Statement
## 3. Goals
## 4. User Personas
## 5. User Stories
## 6. User Flow
## 7. Functional Requirements
## 8. Edge Cases
## 9. Non-functional Requirements
## 10. Metrics
## 11. Rollout Plan
## 12. Risks
```

**Worked example (excerpts):**

```
## 2. Problem Statement
Customers abandon KYC because the process is too long and re-asks for
data we already have.

## 3. Goals
Increase KYC completion rate. [NEEDS INPUT: current baseline and target]

## 5. User Stories
As a customer, I want to upload my ID card so that I can continue the
KYC process without re-entering data.

## 8. Edge Cases
- Blurred image → prompt retake, do not call OCR.
- OCR timeout → show "Please try again", preserve uploaded file.
```

---

## FRD — Functional Requirements Document

BA's workhorse for pinning down exact behavior.

```
# FRD: <title>

## 1. Feature Overview
## 2. Business Rules
## 3. Inputs
## 4. Outputs
## 5. Validation Rules
## 6. Process Flow
## 7. Exception Cases
## 8. Acceptance Criteria
```

**Worked example (excerpts):**

```
## 5. Validation Rules
| Field      | Required | Rule                          |
|------------|----------|-------------------------------|
| ID Number  | Yes      | Exactly 12 digits, numeric    |
| ID Image   | Yes      | JPG/PNG/PDF, ≤ 10MB            |

## 7. Exception Cases
EX-1: OCR service timeout → show "Please try again", keep uploaded file,
      do not advance the step.
EX-2: Unsupported format → reject at upload, show accepted formats.

## 8. Acceptance Criteria
AC-1: Given a valid 12-digit ID and a clear image, when the customer
      submits, then OCR is called and the customer advances to the next step.
```
