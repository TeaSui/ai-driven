# Security Principles

- Never commit secrets, API keys, or credentials
- Validate all user inputs at system boundaries
- Use parameterized queries — no string concatenation in SQL
- Follow principle of least privilege
- Never log PII (mask email, phone, account numbers)
- Sensitive data in platform secure storage only, never plaintext

All implementation agents have embedded security checklists that apply when Security Agent is skipped. Security Agent rules take precedence when provided.
