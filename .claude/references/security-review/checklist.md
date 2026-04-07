# Security Review Checklist

## OWASP Top 10

### 1. Injection
- [ ] SQL queries use parameterized statements
- [ ] NoSQL queries sanitize inputs
- [ ] OS commands avoid user input
- [ ] LDAP queries escape special characters

### 2. Broken Authentication
- [ ] Strong password requirements enforced
- [ ] Multi-factor authentication available
- [ ] Session tokens regenerated after login
- [ ] Failed login attempts rate-limited

### 3. Sensitive Data Exposure
- [ ] Data encrypted at rest (AES-256)
- [ ] Data encrypted in transit (TLS 1.2+)
- [ ] PII handling follows regulations
- [ ] Sensitive data not logged

### 4. XML External Entities (XXE)
- [ ] XML parsing disables external entities
- [ ] DTD processing disabled
- [ ] Use JSON over XML when possible

### 5. Broken Access Control
- [ ] Authorization checked on every request
- [ ] Default deny policy
- [ ] Resource ownership validated
- [ ] CORS configured properly

### 6. Security Misconfiguration
- [ ] Debug mode disabled in production
- [ ] Default credentials changed
- [ ] Unnecessary features disabled
- [ ] Security headers configured

### 7. Cross-Site Scripting (XSS)
- [ ] Output encoding applied
- [ ] Content Security Policy set
- [ ] HttpOnly cookies used
- [ ] User input sanitized

### 8. Insecure Deserialization
- [ ] Untrusted data not deserialized
- [ ] Digital signatures verified
- [ ] Type checking enforced

### 9. Using Components with Known Vulnerabilities
- [ ] Dependencies regularly updated
- [ ] Security advisories monitored
- [ ] Automated vulnerability scanning

### 10. Insufficient Logging & Monitoring
- [ ] Security events logged
- [ ] Logs protected from tampering
- [ ] Alerting configured
- [ ] Incident response plan exists

## Authentication Security
- [ ] Passwords hashed with bcrypt/argon2
- [ ] Salt unique per password
- [ ] Password reset tokens expire
- [ ] Account lockout implemented

## Data Protection
- [ ] Encryption keys properly managed
- [ ] Key rotation policy exists
- [ ] Backups encrypted
- [ ] Data retention policy enforced

## Input Validation
- [ ] Whitelist validation preferred
- [ ] Input length limits set
- [ ] File uploads validated
- [ ] Content-Type headers checked

## Secrets Management
- [ ] No secrets in source code
- [ ] Environment variables for config
- [ ] Secrets rotated regularly
- [ ] Access to secrets audited
