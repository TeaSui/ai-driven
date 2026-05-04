---
name: fintech-security
description: |
  Fintech security advisory patterns for Spring Boot, Go, and AWS serverless stacks.
  MAS TRM compliance controls, payment API security, KYC document handling,
  authentication/authorization patterns, and secrets detection.
  Use when: performing threat modeling or security review for fintech services,
  defining security rules for Spring Boot or Go implementations, reviewing
  payment flows, KYC processing, or API authentication designs.
  Triggers on: security review, threat model, auth flow, payment security,
  KYC, MAS compliance, PDPA, fintech.
---

# Fintech Security Advisory Patterns

## MAS TRM (Technology Risk Management) Controls

### Authentication & Access Control (TRM 9)
Rules to emit for implementation agents:
- AUTH-01: Multi-factor authentication for all customer-facing financial transactions
- AUTH-02: Session timeout ≤15 minutes idle for financial operations, ≤30 minutes for non-financial
- AUTH-03: Device binding — new device requires step-up authentication (OTP + biometric)
- AUTH-04: Transaction signing — high-value transfers require separate confirmation factor
- AUTH-05: Failed login lockout after 5 attempts, progressive delays (1min, 5min, 15min, 1hr)

### Data Protection (TRM 10)
- DATA-01: Encryption at rest with CMK (AWS KMS) for all Level 1 PII
- DATA-02: TLS 1.3 for all inter-service communication
- DATA-03: Database field-level encryption for national ID, account numbers
- DATA-04: Key rotation every 90 days, automated via AWS Secrets Manager
- DATA-05: No PII in logs, URLs, error responses, message queue metadata

### Audit Trail (TRM 11)
- AUDIT-01: Every financial transaction logged with: who, what, when, from-where, result
- AUDIT-02: Immutable audit logs — write to append-only store (CloudWatch + S3 with Object Lock)
- AUDIT-03: Log retention: 7 years for financial transactions, 5 years for access logs
- AUDIT-04: Tamper detection — hash chain or WORM storage for critical audit logs
- AUDIT-05: Real-time alerting on: privilege escalation, bulk data export, admin actions outside hours

### Online Financial Services (TRM 14)
- OFS-01: Transaction limits — daily and per-transaction configurable limits
- OFS-02: Velocity checks — flag unusual transaction frequency or amounts
- OFS-03: Cool-down period for new payee additions (configurable, default 12 hours)
- OFS-04: SMS/push notification for every financial transaction
- OFS-05: Kill switch — ability to disable specific transaction types within minutes

## Spring Boot Security Attack Surfaces

### Common Misconfigurations to Check
```
# STRIDE analysis points for Spring Boot services

SPOOFING:
- JWT validation: verify issuer, audience, expiry, signature algorithm (RS256, not HS256 with shared secret)
- SecurityFilterChain ordering: more specific matchers before generic
- Missing @PreAuthorize on service methods with sensitive operations
- Actuator endpoints exposed without authentication (/actuator/env leaks secrets)

TAMPERING:
- Missing @Valid on request DTOs — allows malformed input to reach service layer
- CSRF disabled but using cookie-based auth (only safe with stateless JWT)
- Missing request size limits — spring.servlet.multipart.max-file-size unbounded
- Deserialization of untrusted JSON — Jackson polymorphic type handling

INFORMATION DISCLOSURE:
- server.error.include-stacktrace=always in production
- spring.jpa.show-sql=true in production
- Actuator /actuator/beans, /actuator/env, /actuator/configprops exposed
- Exception messages containing table names, query fragments

DENIAL OF SERVICE:
- No rate limiting on authentication endpoints
- Missing pagination on list endpoints (SELECT * with no LIMIT)
- File upload without size limit
- Regex in @Pattern without catastrophic backtracking protection
```

### Spring Security Rules Template
```
# Rules for Backend Engineer (Spring Boot)
SEC-SPRING-01: SecurityFilterChain must disable CSRF for stateless APIs, enable for web forms
SEC-SPRING-02: All endpoints authenticated by default (.anyRequest().authenticated())
SEC-SPRING-03: Actuator endpoints: only /health and /info public, all others require ADMIN
SEC-SPRING-04: JWT validation must check: signature, expiry, issuer, audience claims
SEC-SPRING-05: @PreAuthorize for method-level authorization on sensitive operations
SEC-SPRING-06: Request body size limit: 1MB default, 10MB for file uploads (configurable)
SEC-SPRING-07: CORS: explicit allowed origins, never "*" in production
SEC-SPRING-08: No server.error.include-stacktrace in production profiles
SEC-SPRING-09: Rate limiting on /auth/** endpoints (spring-boot-starter-cache + Bucket4j or API Gateway)
SEC-SPRING-10: Input validation via @Valid + Bean Validation on all @RequestBody parameters
```

## Go Security Pitfalls

### Common Vulnerabilities
```
# STRIDE analysis points for Go services

SPOOFING:
- crypto/tls: must set MinVersion to tls.VersionTLS12
- JWT libraries: use github.com/golang-jwt/jwt/v5 (not dgrijalva/jwt-go, abandoned)
- Verify algorithm in JWT header matches expected (prevent algorithm confusion attack)
- HTTP client: default has no timeout — set explicitly

TAMPERING:
- sql.DB: always use parameterized queries ($1, $2), never fmt.Sprintf
- Template injection: use html/template (auto-escapes), never text/template for HTML
- File path: filepath.Clean() + validate path doesn't escape base directory
- Integer overflow: Go integers wrap silently — validate before financial calculations

INFORMATION DISCLOSURE:
- recover() in HTTP handlers: log panic, return generic 500 (never expose panic message)
- Error wrapping: fmt.Errorf("failed: %w", err) — ensure inner error doesn't contain secrets
- Debug endpoints: pprof should never be exposed on public port

DENIAL OF SERVICE:
- http.Server: always set ReadTimeout, WriteTimeout, IdleTimeout
- Request body: use http.MaxBytesReader() to limit request size
- goroutine leaks: ensure all goroutines have exit conditions
- JSON decoding: use json.Decoder with DisallowUnknownFields + MaxBytes
```

### Go Security Rules Template
```
# Rules for Backend Engineer (Go)
SEC-GO-01: HTTP server must set ReadTimeout(10s), WriteTimeout(30s), IdleTimeout(120s)
SEC-GO-02: All SQL queries use parameterized statements, never string concatenation
SEC-GO-03: TLS MinVersion: tls.VersionTLS12, prefer TLS 1.3 cipher suites
SEC-GO-04: Request body limited via http.MaxBytesReader (1MB default)
SEC-GO-05: All goroutines must have context-based cancellation
SEC-GO-06: Financial calculations use math/big or shopspring/decimal, never float64
SEC-GO-07: Error responses to clients: generic messages only, detailed errors in logs
SEC-GO-08: JWT: use golang-jwt/jwt/v5, validate algorithm + claims explicitly
SEC-GO-09: pprof/debug endpoints on separate port (6060), never on API port
SEC-GO-10: Input validation at handler level before passing to business logic
```

## Payment API Security Patterns

### Transaction Security Rules
```
PAY-01: Idempotency keys required on all payment endpoints (client-generated UUID v4)
PAY-02: Double-entry bookkeeping — every debit has a corresponding credit
PAY-03: Optimistic locking on account balances (version column or conditional DynamoDB writes)
PAY-04: Transaction amount validation: positive, max decimal places (2 for SGD), within limits
PAY-05: Currency handling: store as minor units (cents) to avoid floating-point errors
PAY-06: Payment state machine: INITIATED → PENDING → COMPLETED/FAILED (no backward transitions)
PAY-07: Reconciliation: async job compares internal ledger with payment provider daily
PAY-08: PCI DSS: never store CVV, full PAN; use tokenization (Stripe, Adyen tokens)
PAY-09: 3DS2 for card payments where applicable
PAY-10: Webhook signature verification for payment provider callbacks (HMAC-SHA256)
```

## KYC Document Security

### Document Handling Rules
```
KYC-01: Document upload: signed S3 URLs with 5-minute expiry, direct browser-to-S3
KYC-02: Document storage: separate S3 bucket, SSE-KMS with CMK, no public access
KYC-03: Document access: pre-signed URLs for viewing, 5-minute expiry, audit logged
KYC-04: Document retention: encrypt, retain 5 years post-relationship, then hard delete
KYC-05: OCR/verification: process in-memory, never persist extracted PII to temp files
KYC-06: Document types: validate MIME type server-side (not just extension)
KYC-07: Document size: max 10MB per document, malware scan before processing
KYC-08: Metadata: store document type + upload timestamp, never PII in S3 object keys
```

## Secrets Detection Patterns

### What to Check in Code Review
```
# Patterns that indicate leaked secrets
SECRETS-01: AWS access keys (AKIA[0-9A-Z]{16})
SECRETS-02: Private keys (-----BEGIN (RSA |EC )?PRIVATE KEY-----)
SECRETS-03: JWT tokens (eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.)
SECRETS-04: Database connection strings with passwords
SECRETS-05: API keys in application.yml/application.properties without ${ENV_VAR} wrapper
SECRETS-06: Hardcoded OTP seeds, encryption keys, HMAC secrets
SECRETS-07: .env files committed to repository

# Recommended CI tools
Pre-commit: gitleaks (fast, configurable)
CI pipeline: truffleHog (deep history scan), detect-secrets (baseline mode)
AWS: aws-vault for local development (never raw AWS credentials in env)
```

## AWS Serverless Security Rules

```
AWS-SEC-01: Lambda env vars: no secrets — use SSM Parameter Store (SecureString) or Secrets Manager
AWS-SEC-02: API Gateway: authorizer on every method, WAF for public APIs
AWS-SEC-03: DynamoDB: encryption with CMK for tables storing Level 1 PII
AWS-SEC-04: S3: BlockPublicAccess on all buckets, bucket policy denies non-HTTPS
AWS-SEC-05: SQS: encrypted at rest (SSE-SQS minimum, SSE-KMS for PII)
AWS-SEC-06: Lambda: VPC-attached only when accessing VPC resources (RDS, ElastiCache)
AWS-SEC-07: IAM: no wildcard resources, scope to specific ARNs
AWS-SEC-08: CloudTrail: enabled in all regions, log file validation on
AWS-SEC-09: Secrets Manager: automatic rotation enabled, rotation ≤90 days
AWS-SEC-10: EventBridge: validate event source in Lambda handler, don't trust event schema blindly
```

## Threat Model Output Format

When defining security rules, emit in this structure:
```
## Threat Model: [Feature Name]

### Assets
- [List data assets and their PII classification level]

### STRIDE Analysis
- [Each category with specific threats identified]

### Security Rules
- [Numbered rules per implementation domain: SEC-SPRING-XX, SEC-GO-XX, SEC-AWS-XX, PAY-XX, KYC-XX]

### Compliance
- MAS TRM: [Applicable controls]
- PDPA: [Data handling requirements]

### Monitoring Rules
- [DataDog monitors to create for security events]
```
