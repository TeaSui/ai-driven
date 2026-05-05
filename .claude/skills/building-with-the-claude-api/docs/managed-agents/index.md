---
tags: [moc, managed-agents]
---

# Managed Agents

Anthropic's hosted agent runtime — a separate product from the Messages API. Pages here cover agent-specific concerns; shared topics (Files, Skills, MCP) link out to the canonical sections.

## First steps
- [[overview]] — what Managed Agents is.
- [[get-started]] — first agent, first session.
- [[prototype-in-console]] — iterate in the Console UI.

## Define your agent
- [[agent-setup]] — agent resource, model, system prompt.
- [[tools]] — declaring tools on the agent.
- [[mcp-connector-for-agents]] — per-agent MCP servers + per-session vault auth. See also canonical [[../mcp/index|MCP]].
- [[permission-policies]] — action-level allow/deny rules.
- [[agent-skills-for-agents]] — attaching Skills to an agent. See also canonical [[../skills/index|Skills]].

## Configure the environment
- [[cloud-environment-setup]] — network, egress, compute.
- [[container-reference]] — container spec.

## Delegate work
- [[start-a-session]] — create a session against an agent.
- [[session-event-stream]] — consuming the SSE event stream.
- [[define-outcomes]] — success signals, completion criteria.
- [[authenticate-with-vaults]] — vault model for per-session secrets.
- [[accessing-github]] — GitHub app access for agents.
- [[adding-files]] — file inputs to sessions. See canonical [[../files/index|Files]].
- [[using-agent-memory]] — session/persistent memory for agents.

## Advanced
- [[multiagent-sessions]] — orchestrating multiple agents in one session.

## Shared pages (canonical lives elsewhere)
- [[../files/index|Files]] — Files API, PDF, images.
- [[../skills/index|Agent Skills]] — author, publish, attach.
- [[../mcp/index|MCP]] — remote MCP servers + connector.

## Related
- [[../index|Docs home]]
