# Security Review Checklist

Default edition: **OWASP Top 10:2025**, verified against the [official list](https://top10.owasp.org/2025/). Follow an explicitly project-required edition instead and label every category ID with that edition. Use [the mapping procedure](owasp-mapping.md), including its official 2021/2017 paths; never transfer category numbers between editions by position.

These controls are review prompts, not automatic findings or a complete compliance standard. Determine applicability from the system's assets, trust boundaries and threat model. Record evidence, gaps and justified non-applicability; preserve relevant mobile, infrastructure, AI and regulatory risks outside the web Top 10.

## A01:2025 — Broken Access Control

- [ ] Authorization and resource ownership checked at every relevant boundary; default deny
- [ ] Tenant separation and privilege changes tested with authorized test identities
- [ ] CORS restricted to approved origins; cookie/ambient-credential flows retain applicable CSRF protection
- [ ] Server-side URL fetches enforce destination/access restrictions (SSRF is included here in 2025)

## A02:2025 — Security Misconfiguration

- [ ] Production debug features and unnecessary services disabled; default credentials removed
- [ ] Security headers and environment-specific settings verified
- [ ] XML parsers disable external entities and unnecessary DTD processing; prefer simpler formats when appropriate
- [ ] Storage, network and runtime permissions/configuration reviewed for unintended exposure

## A03:2025 — Software Supply Chain Failures

- [ ] Dependencies tracked; versions and advisories reviewed with the project's scanner/update policy
- [ ] Build dependencies, sources and distribution artifacts come from approved origins
- [ ] CI credentials and release/signing paths use scoped access; suspicious dependency/build changes investigated

## A04:2025 — Cryptographic Failures

- [ ] Sensitive data and backups encrypted with project-approved algorithms and key management
- [ ] TLS configured for supported endpoints and clients; applicable protocol/security requirements verified
- [ ] Secret/key access restricted and audited; rotation and recovery procedures defined
- [ ] Data classification, retention and deletion follow verified project/jurisdiction requirements

## A05:2025 — Injection

- [ ] SQL queries parameterized; NoSQL query structure and operators validated
- [ ] OS commands avoid untrusted command text; LDAP inputs escaped with the appropriate API
- [ ] Contextual output encoding prevents XSS; trusted sanitization used only where HTML is required
- [ ] Content Security Policy supports defense in depth; it does not replace safe rendering

## A06:2025 — Insecure Design

- [ ] Threat model addresses trust boundaries, abuse cases and security-sensitive business invariants
- [ ] Least privilege, resource limits and recovery behavior are designed before implementation
- [ ] Retry, idempotency and transactional boundaries preserve business effects through failures

## A07:2025 — Authentication Failures

- [ ] Passwords use an approved adaptive password-hashing scheme (for example Argon2id or an approved bcrypt configuration) with unique salts
- [ ] Password policy, MFA and abuse controls match the project's risks; rate limiting/lockout cannot trivially deny service to victims
- [ ] Session identifiers rotate after login/privilege changes; token expiry/revocation and logout are tested
- [ ] Password-reset tokens are scoped, time-limited and single-use; cookies have appropriate Secure/HttpOnly/SameSite settings

## A08:2025 — Software or Data Integrity Failures

- [ ] Untrusted serialized input cannot instantiate arbitrary types or executable behavior
- [ ] Types, signatures and integrity metadata verified where the trust model requires them
- [ ] Updates, artifacts and state transitions cannot bypass the intended verification boundary

## A09:2025 — Security Logging and Alerting Failures

- [ ] Relevant security events logged with safe identifiers, timestamps and outcomes
- [ ] Credentials, raw sensitive payloads and PII excluded/redacted; logs protected against tampering and unauthorized access
- [ ] Actionable alerts tested with assigned responders; incident response procedures exist

## A10:2025 — Mishandling of Exceptional Conditions

- [ ] Unexpected errors fail securely; clients do not receive stack traces, secrets or internal details
- [ ] Timeouts, exhausted resources, partial failures and cleanup paths preserve security/business invariants
- [ ] Retry limits, fallback behavior and recovery are tested; unexplained 5xx responses are investigated with redacted reproduction evidence

## Cross-cutting input and secret checks

- [ ] Allowlist validation, input length/depth limits, upload checks and Content-Type validation applied at relevant boundaries
- [ ] No secrets committed to source, images or diagnostic output; non-secret config kept separate from secret retrieval
- [ ] Runtime secret retrieval uses the project-approved store and scoped access; identifiers are not confused with secret values
- [ ] Every applicable control maps to a concrete finding/rule or evidence-backed result; a checked box alone is not a security verdict
