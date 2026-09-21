# Specification Document (Spec)

The most detailed document — and the one BA, PM, developers, and AI agents all rely on most heavily, because it's precise enough to implement and to generate code from with few errors. A good spec leaves almost nothing to guesswork: contracts, schemas, error handling, and test cases are all explicit.

In this repo's workflow the spec is a TechLead artifact (`docs/contracts/`). It complements, rather than replaces, an OpenAPI/JSON-Schema contract — the spec is the human-readable feature definition; the contract files are the machine-validated interface.

## Structure

```
# Specification: <title>

## 1. Overview
## 2. Scope
## 3. Business Flow
## 4. User Flow
## 5. API Contract
## 6. Event Contract
## 7. Database Schema
## 8. Business Rules
## 9. Error Handling
## 10. Security
## 11. Rollout
## 12. Monitoring
## 13. Test Cases
## 14. Open Questions
```

Sections 5–7 (the contracts) are what make a spec codegen-ready. Be exact: field names, types, required/optional, formats. Vagueness here is where generated code goes wrong.

## API contract format

The response envelope is **mandatory for every HTTP-exposed endpoint** per `rules/patterns.md`: success `{ "data": {...}, "meta": {"timestamp": "..."} }`, error `{ "error": {"code": "...", "message": "...", "details": []} }`. Show request and both response shapes for each endpoint. If a third-party contract (e.g. a vendor-signed webhook) forces a different shape, wrap it at ingress and translate to the envelope internally — note that in the spec.

## Worked example (excerpts)

```
## 5. API Contract
POST /kyc/documents
Request (multipart/form-data):
  file: binary, required, JPG|PNG|PDF, ≤ 10MB
  idNumber: string, required, /^[0-9]{12}$/
Success 201:
  { "data": { "documentId": "uuid", "status": "PROCESSING" },
    "meta": { "timestamp": "..." } }
Error 422:
  { "error": { "code": "INVALID_FORMAT",
               "message": "Unsupported file type",
               "details": ["accepted: JPG, PNG, PDF"] } }

## 6. Event Contract
kyc.document.processed (v1):
  { "eventId": "uuid", "customerId": "123",
    "documentId": "uuid", "status": "PASSED",
    "timestamp": "..." }

## 7. Database Schema
kyc_documents:
  id              uuid PK
  customer_id     uuid FK NOT NULL
  status          enum(PROCESSING, PASSED, FAILED) NOT NULL
  created_at      timestamptz NOT NULL
  updated_at      timestamptz NOT NULL

## 10. Security
PII fields: name, email, phone → mask in logs, encrypt at rest.
AuthZ: only the owning customer or a KYC-reviewer role may read a document.

## 13. Test Cases
TC-1: valid upload → 201, status PROCESSING, event emitted
TC-2: 8-digit idNumber → 422 INVALID_FORMAT
TC-3: 12MB file → 422 INVALID_FORMAT
TC-4: OCR timeout → status FAILED, retry-able, no PII in error log
```

A spec where Security (§10) and Test Cases (§13) are filled in with this much specificity is what lets implementation and QA proceed in parallel without back-and-forth.
