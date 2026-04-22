# Stuck Run Troubleshooting

## Symptoms
- Task stuck in "coding" or "planning" for >20 minutes
- No new WebSocket events
- Dashboard shows task as "running" but no progress

## Steps

### 1. Check task status

```bash
curl http://your-domain/api/admin/tasks/{task_id} \
  -H "X-API-Key: aidrv_your_key"
```

Check the `status`, `startedAt`, and `iterations` fields.

### 2. Check WebSocket stream for errors

Open the task detail page in the dashboard — look for any error events in the timeline.

### 3. Cancel the stuck task

```bash
curl -X POST http://your-domain/api/chat/tasks/{task_id}/stop \
  -H "X-API-Key: aidrv_your_key"
```

This sets the task to `cancelled` and publishes a stop event.

### 4. Check for system issues

- **Redis**: Is the Redis instance healthy? Check `/health` endpoint.
- **Queue depth**: If using SQS workers, check queue depth in CloudWatch.
- **DLQ**: Check if the task message ended up in the dead-letter queue.
- **Budget**: Has the daily budget been exceeded? Check `/api/admin/usage`.

### 5. Retry the task

Create a new task with the same message:

```bash
curl -X POST http://your-domain/api/chat/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: aidrv_your_key" \
  -d '{
    "message": "same message as before",
    "agentType": "coding",
    "context": {}
  }'
```

### 6. Escalate

If tasks consistently get stuck, collect:
- Task ID(s)
- Timestamps
- `/health` endpoint output
- Any error events from the WebSocket stream

Contact the platform team with this information.

## Common causes

| Cause | Indicator | Fix |
|-------|-----------|-----|
| Claude API timeout | Task has 0 iterations | Check Anthropic/Bedrock status |
| Redis disconnection | No WebSocket events | Restart Redis or check network |
| Task timeout (20min) | Status changes to "failed" with timeout message | Task was too complex — break into smaller tasks |
| Budget exceeded | 429 on new tasks | Wait for next day or increase budget |
