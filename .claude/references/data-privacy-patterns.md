# Data Privacy Rules (Fintech/TYME Context)

## PII Classification

**Level 1 — Highly Sensitive (encrypt at rest + in transit, audit every access):**
- National ID / passport number
- Financial account numbers, card numbers
- Biometric data (fingerprint, face ID hashes)
- Full date of birth + name combination
- Authentication credentials (passwords, PINs, OTPs)

**Level 2 — Sensitive (encrypt at rest + in transit):**
- Email address
- Phone number
- Physical address
- Transaction amounts and history
- KYC status and documents
- Device identifiers linked to users

**Level 3 — Internal (encrypt in transit):**
- Anonymized/aggregated analytics
- System logs with user IDs only
- Feature flags per user segment

## Data Handling Rules

**Collection:**
- Collect minimum necessary data (data minimization principle)
- Explicit consent required before collecting Level 1/2 data
- Document purpose of collection for each data type
- No collecting PII as part of debug/logging without anonymization

**Storage:**
- Level 1 data: encrypted with customer-managed KMS keys (AWS KMS CMK)
- Level 2 data: encrypted with AWS-managed KMS keys minimum
- Never store card numbers, CVV, or full PAN — use tokenization (e.g., Stripe tokens)
- Retention periods must be defined and enforced:
  - Transaction records: 7 years (regulatory requirement)
  - KYC documents: 5 years post-relationship end
  - Session data: 90 days
  - Logs containing user data: 1 year

**Processing:**
- Never log raw PII — mask or truncate in logs
  - Email: `user@***.com`
  - Phone: `+65****1234`
  - Account: `****5678`
- No PII in error messages returned to clients
- No PII in URLs or query parameters (use POST body or encrypted tokens)

**Transmission:**
- TLS 1.2 minimum, TLS 1.3 preferred
- No PII in GET request URLs
- Encrypt PII in message queues (SQS) at rest
- API responses must not include more PII than the request scope requires

## Database Patterns

```typescript
// DynamoDB — encrypt sensitive fields at application layer
interface UserRecord {
  PK: string;           // USER#<userId> — no PII in key
  SK: string;           // METADATA
  name: string;         // encrypted at application level for Level 1
  email: string;        // encrypted
  kycStatus: string;    // 'PENDING' | 'APPROVED' | 'REJECTED'
  // NEVER store: rawCardNumber, cvv, pin, password
}

// Separate PII table from transactional data
// Reference by userId only in transaction tables
```

## Anonymization & Masking

```typescript
// Log masking utility
const maskEmail = (email: string): string =>
  email.replace(/(.{2}).*(@.*)/, '$1***$2');

const maskPhone = (phone: string): string =>
  phone.replace(/(\+\d{2})\d+(\d{4})$/, '$1****$2');

const maskAccountNumber = (account: string): string =>
  account.replace(/.(?=.{4})/g, '*');
```

## Cross-Border Data Transfer
- User data of Singapore residents: prefer `ap-southeast-1` (Singapore) region
- Cross-region replication requires DPA (Data Processing Agreement)
- No PII to regions with inadequate data protection laws without legal approval
- Document all third-party data processors (Stripe, AWS, Anthropic etc.)

## LLM / AI Usage with PII
- Never send raw PII to external LLM APIs (including Anthropic Claude API)
- Anonymize/pseudonymize before analysis: replace names with `[USER_A]`, etc.
- Use on-premise or VPC-hosted models for processing PII when required
- Internal Bedrock endpoint (current setup) is acceptable for PII processing with proper DPA

## Data Subject Rights
- Right to access: must return all data held about a user within 30 days
- Right to deletion: implement hard delete + backup purge for Level 1/2 data
- Right to portability: data export in standard format (JSON/CSV)
- Implement a `deleteUser(userId)` function that is tested and documented

## Breach Response
- Data breach detection: alert within 1 hour of detection
- Internal notification: CTO + Legal within 2 hours
- Regulatory reporting: MAS (Monetary Authority of Singapore) within 30 days
- Customer notification: within 30 days if high risk to individuals
- Maintain incident log with: detection time, scope, affected records count, remediation

## Code Review Checklist (Data Privacy)
- [ ] No PII in log statements
- [ ] No PII in error messages
- [ ] No PII in URL parameters
- [ ] Sensitive fields encrypted at rest
- [ ] Retention policy applied to new data stores
- [ ] Data minimization: only collecting what's needed
- [ ] Third-party integrations reviewed for PII sharing
