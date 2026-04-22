# Add a Repository

## Prerequisites
- Team API key (starts with `aidrv_`)
- Repository exists on GitHub or Bitbucket
- You have at least read access to the repository

## Steps

### 1. Register the repository

```bash
curl -X POST http://your-domain/api/admin/repos \
  -H "Content-Type: application/json" \
  -H "X-API-Key: aidrv_your_key_here" \
  -d '{
    "fullName": "org/repo-name",
    "scmType": "github",
    "defaultBranch": "main"
  }'
```

**Response (201 Created):**
```json
{
  "id": "uuid",
  "teamId": "uuid",
  "fullName": "org/repo-name",
  "scmType": "github",
  "defaultBranch": "main",
  "isActive": true,
  "createdAt": "2026-01-01T00:00:00Z"
}
```

### 2. Verify registration

```bash
curl http://your-domain/api/admin/repos \
  -H "X-API-Key: aidrv_your_key_here"
```

### 3. Test by sending a task

```bash
curl -X POST http://your-domain/api/chat/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: aidrv_your_key_here" \
  -d '{
    "message": "List all TODO comments in the codebase",
    "agentType": "coding",
    "context": {"repoFullName": "org/repo-name"}
  }'
```

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `409 Conflict` | Repo already registered for your team | Use `GET /api/admin/repos` to check |
| `401 Unauthorized` | Invalid or missing API key | Check `X-API-Key` header |
| `422 Validation Error` | Invalid `scmType` or missing `fullName` | Use `github` or `bitbucket` for scmType |
