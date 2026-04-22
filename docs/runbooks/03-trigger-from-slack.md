# Trigger a Task from Slack

## Prerequisites
- Slack app installed in your workspace
- Bot has access to the channel
- `SLACK_BOT_TOKEN` and `SLACK_SIGNING_SECRET` configured on the server

## Steps

### 1. Mention the bot with a Jira key

In any channel the bot is in:

```
@ai-driven fix PROJ-123
```

The bot will:
1. Extract the Jira key (`PROJ-123`)
2. Dispatch a coding agent task
3. Reply in a thread with progress updates
4. Post a Block Kit PR card when complete

### 2. Supported commands

| Command | Agent | Description |
|---------|-------|-------------|
| `@ai-driven fix PROJ-123` | coding | Fix the issue described in the Jira ticket |
| `@ai-driven review PROJ-456` | review | Review the PR linked to the ticket |
| `@ai-driven deploy PROJ-789` | devops | Handle deployment for the ticket |

### 3. Monitor progress

The bot posts threaded updates:
- **Planning** — agent is analyzing the task
- **Coding** — agent is writing code
- **Testing** — running tests
- **Complete** — PR created with link

### Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| Bot doesn't respond | Missing channel permissions | Invite bot to channel: `/invite @ai-driven` |
| "Unknown Jira key" | Ticket doesn't exist or Jira not configured | Check `JIRA_BASE_URL` and `JIRA_API_TOKEN` env vars |
| Task fails | Agent error | Check task detail in dashboard for error message |
