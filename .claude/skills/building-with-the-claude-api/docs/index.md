---
tags: [moc, claude-api]
aliases: [Home, Claude API Docs]
---

# Claude API Docs — Home

Source-of-truth reference material for the Anthropic Python SDK, curated into a flat Obsidian graph. Each section folder has its own `index.md` (Map of Content). For the distilled, task-oriented skill layer, see [`../SKILL.md`](../SKILL.md) and [`../references/`](../references/).

## Sections

### Core Messages API
- [[messages-api/index|Messages API]] — setup, message shape, system prompts, temperature, stop reasons.
- [[model-capabilities/index|Model capabilities]] — thinking, effort, structured outputs, citations, streaming, batch, embeddings.

### Tools & extensions
- [[tools/index|Tools]] — client-side custom tools and Anthropic-hosted server/client tools (web search, bash, memory, computer use, text editor, …).
- [[tool-infrastructure/index|Tool infrastructure]] — tool search, programmatic calling, fine-grained streaming, tool combinations, context.
- [[mcp/index|MCP]] — remote MCP servers and the MCP connector (Messages-API framing).

### Context, files, skills
- [[context-management/index|Context management]] — windows, compaction, context editing, prompt caching, token counting.
- [[files/index|Files]] — Files API, PDF, images/vision.
- [[skills/index|Agent Skills]] — pre-built skills, authoring, enterprise, using Skills with the API.

### Agents & platforms
- [[managed-agents/index|Managed Agents]] — Anthropic's hosted agent runtime (separate product from the Messages API).
- [[third-party-platforms/index|Third-party platforms]] — Bedrock, Microsoft Foundry, Vertex AI.

### Practices & operations
- [[best-practices/index|Best practices]] — prompt engineering, evaluations, guardrails, use cases, glossary.
- [[admin/index|Admin]] — org/workspaces, authentication (WIF + identity providers), monitoring, data & compliance.

## How to read this graph

- **Start at a section `index.md`** — it lists every page in that folder with a one-line hook.
- **Follow `[[wiki-links]]`** between related topics. Obsidian's graph view will show the cluster.
- **Duplicate pages have been canonicalized.** Where Managed Agents and Messages-API docs overlapped (Files, Skills, MCP), a single canonical page exists under the primary section and Managed Agents cross-links to it. Agent-specific framing lives under [[managed-agents/index|managed-agents/]] only when the content genuinely differs.
