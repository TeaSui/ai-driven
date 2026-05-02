# MCP Servers — Dev Tooling

This project ships a project-scoped `.mcp.json` that registers two MCP servers for Claude Code. These are **development tools** — they run locally in your Claude Code session, *not* in the deployed application.

The deployed coding + review agents already use their own MCP client manager (`backend/src/ai_driven/mcp/`) that talks to the GitHub MCP server via stdio. That's separate from this file.

## Registered servers

### `aws-docs` — read-only AWS documentation search

- **Package:** [`awslabs.aws-documentation-mcp-server`](https://pypi.org/project/awslabs.aws-documentation-mcp-server/)
- **Why:** Stack runs on Lambda, ECS Fargate, Aurora, SQS, DynamoDB, API Gateway. When triaging an incident or tuning CDK, faster to look up docs via MCP than to keep the AWS console + search tabs open.
- **Permissions:** read-only. Does not touch your AWS account.
- **Secrets:** none.

### `atlassian` — Jira read/write

- **Package:** [`mcp-atlassian`](https://pypi.org/project/mcp-atlassian/)
- **Why:** Complements the existing `JiraClient` used by the coding agent. Lets you (and Claude) look up tickets, update statuses, and read comments directly from a chat session instead of context-switching.
- **Permissions:** scoped by the API token you provide.
- **Secrets:** reads `AI_DRIVEN_JIRA_BASE_URL`, `AI_DRIVEN_JIRA_EMAIL`, `AI_DRIVEN_JIRA_API_TOKEN` from your shell env (or `.env.local` if you `export $(grep -v '^#' .env.local | xargs)` before launching Claude Code). If unset at startup, the server still launches — tool calls will return auth errors until you set them.

## Opting out

Delete `.mcp.json`. Nothing in the project code path reads it.

To opt out of *just one* server, remove that server's entry from `.mcp.json` and restart Claude Code.

## Why not Playwright MCP?

Asked and answered: the project already has `@playwright/test` running 32 real E2E tests. MCP Playwright is for ad-hoc browser driving inside a Claude conversation — useful for building scraping agents or UI research, not for a project with a CI-grade test runner. See prior session notes.

## Related

- `backend/src/ai_driven/mcp/` — the coding + review agents' MCP client manager (GitHub MCP via stdio). Unrelated to `.mcp.json`.
- `docs/ops/configuration.md` — for `AI_DRIVEN_JIRA_*` env var wiring into the backend.
