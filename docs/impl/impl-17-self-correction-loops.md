# impl-17: Self-Correction Loops
**Status**: DONE

## Context & Motivation
Currently, when the AI agent generates code, the process ends when the branch is pushed and the PR is open. It does not actively wait for automated test execution to finish, and cannot fix any CI/CD failures its own code caused.

## Proposed Strategy
1. **CI Webhook Ingestion**: Add new event types to the `AgentWebhookHandler` payload checking logic. GitHub Actions/Bitbucket Pipelines webhooks are sent to `API Gateway`.
2. **Failure Analysis**: Upon receiving a `workflow_run.completed=failure` webhook, find the originating Jira ticket.
3. **ReAct Task Queuing**: Feed the CI build logs or parsed test failure summaries to the SQS FIFO queue with `AI_COMMAND` intent.
4. **Correction Logic**: The agent uses its existing MCP codebase tools (via `CodeContextToolProvider`) to identify the buggy generated code, commit fixes, and auto-push corrections back to the branch.

## Definition of Done
- CI failure webhooks invoke the `AgentProcessorHandler`.
- Agent correctly parses the test output from the GitHub Actions context.
- Agent pushes a remediation commit to the open PR.
