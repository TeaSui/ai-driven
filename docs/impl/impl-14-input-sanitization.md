# impl-14: Input Sanitization (4.3)

**Status:** 🔲 To Do  
**Priority:** 4.3  
**Impact:** Prevent prompt injection and abuse via malicious Jira tickets

---

## Goal

- Validate and sanitize Jira ticket content before prompt injection
- Detect and reject tickets with potentially malicious instructions
- Rate limit per-user webhook triggers

## Proposed Changes

### Input Sanitization
- [ ] Create `InputSanitizer` utility in `core` module
- [ ] Strip HTML/script tags from ticket descriptions
- [ ] Remove Jira markup that could confuse Claude
- [ ] Detect and neutralize prompt injection patterns:
  | Pattern | Action |
  |---------|--------|
  | `ignore previous instructions` | Flag + sanitize |
  | `system:` or `<system>` | Strip |
  | Excessive special characters | Truncate |
  | Base64-encoded content | Decode and re-validate |
- [ ] Log flagged tickets for manual review

### Rate Limiting
- [ ] Create `RateLimiter` using DynamoDB atomic counters
- [ ] Track: requests per user per hour
- [ ] Add `MAX_REQUESTS_PER_USER_PER_HOUR` env var (default: `10`)
- [ ] Return 429 when limit exceeded

### Webhook Validation
- [ ] Validate Jira webhook signatures (HMAC)
- [ ] Validate Bitbucket webhook signatures
- [ ] Reject requests with invalid or missing signatures

### Integration into Handlers
- [ ] `JiraWebhookHandler` — rate limit check + signature validation
- [ ] `FetchTicketHandler` — sanitize ticket content before passing downstream
- [ ] `MergeWaitHandler` — validate Bitbucket webhook signature

### CDK Stack
- [ ] Add `MAX_REQUESTS_PER_USER_PER_HOUR` to `lambdaEnvironment`
- [ ] Add `JIRA_WEBHOOK_SECRET` and `BITBUCKET_WEBHOOK_SECRET` secrets

## Testing Strategy

- [ ] Unit test `InputSanitizer` with known injection patterns
- [ ] Unit test `RateLimiter` with concurrent requests
- [ ] Unit test webhook signature validation
- [ ] Penetration test: submit crafted ticket and verify sanitization

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/security/InputSanitizer.java` |
| NEW | `core/src/main/java/com/aidriven/core/security/RateLimiter.java` |
| NEW | `core/src/main/java/com/aidriven/core/security/WebhookValidator.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/JiraWebhookHandler.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/FetchTicketHandler.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/MergeWaitHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
