# Rotate API Key

## When to rotate
- Suspected key compromise
- Team member departure
- Routine rotation (every 90 days recommended)

## Steps

### 1. Rotate the key

```bash
curl -X POST http://your-domain/api/admin/teams/{team_id}/rotate-key \
  -H "X-API-Key: aidrv_current_key"
```

**Response (200 OK):**
```json
{
  "apiKey": "aidrv_new_key_here",
  "oldKeyExpiresAt": "2026-04-24T12:00:00Z",
  "teamId": "uuid"
}
```

> **Important:** Copy the new API key immediately. It is shown only once.

### 2. Grace period

The old key continues to work for **48 hours** after rotation. During this window:
- The old key logs a deprecation warning on each use
- Both old and new keys are valid
- After 48 hours, the old key stops working permanently

### 3. Update all integrations

Update the API key in:
- CI/CD pipelines
- Slack bot configuration
- Jira webhook settings
- Any scripts or tools using the key

### 4. Verify new key works

```bash
curl http://your-domain/api/admin/dashboard \
  -H "X-API-Key: aidrv_new_key_here"
```

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| Old key stops working early | Redis was restarted (grace keys stored in Redis) | Use the new key immediately after rotation |
| `401` after rotation | Using neither old nor new key | Check the key value carefully |
