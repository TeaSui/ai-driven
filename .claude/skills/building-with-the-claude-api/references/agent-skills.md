# Agent Skills

Modular, filesystem-based capabilities (SKILL.md + scripts + resources) loaded on-demand via progressive disclosure. Pre-built Anthropic Skills: `pptx`, `xlsx`, `docx`, `pdf`. Also custom Skills you upload.

## Contents

- [What Skills are](#what-skills-are)
- [Using pre-built Skills (API)](#using-pre-built-skills-api)
- [Uploading custom Skills](#uploading-custom-skills)
- [Authoring best practices](#authoring-best-practices)
- [Enterprise governance](#enterprise-governance)
- [Sources](#sources)

## What Skills are

A Skill = `SKILL.md` (frontmatter + body) optionally plus scripts and bundled files inside a folder. Claude loads the metadata at startup, reads the body when the description triggers, and pulls bundled files on demand.

```yaml
# SKILL.md
---
name: processing-pdfs
description: Extract text and tables from PDFs, fill forms, merge files. Use when the user mentions PDFs or PDF forms.
---
# Processing PDFs

## Extract text
```python
import pdfplumber
with pdfplumber.open("f.pdf") as pdf:
    text = pdf.pages[0].extract_text()
```
```

Gotchas:
- NOT ZDR-eligible.
- `name`: ≤ 64 chars, lowercase + digits + hyphens, cannot contain "anthropic" or "claude".
- `description`: ≤ 1024 chars — include WHAT + WHEN.
- Max **8 Skills per API request**.
- API requires 3 beta headers: `code-execution-2025-08-25`, `skills-2025-10-02`, `files-api-2025-04-14`.
- Custom Skills DO NOT sync across claude.ai / Claude Code / API — each surface is a separate store.

## Using pre-built Skills (API)

Pre-built IDs: `pptx`, `xlsx`, `docx`, `pdf`. Use when you need doc generation without writing code.

```python
skills = client.beta.skills.list(source="anthropic")

response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    betas=["code-execution-2025-08-25", "skills-2025-10-02", "files-api-2025-04-14"],
    container={"skills": [{"type": "anthropic", "skill_id": "pptx", "version": "latest"}]},
    tools=[{"type": "code_execution_20250825", "name": "code_execution"}],
    messages=[{"role": "user", "content": "Create a 5-slide deck on renewable energy"}],
)

# Download generated output
for block in response.content:
    if block.type == "code_execution_output":
        content = client.beta.files.download(file_id=block.file_id)
        content.write_to_file("out.pptx")
```

Gotchas:
- Code execution tool is **required** — Skills run inside the container.
- Files API beta is required to download output.
- `version: "latest"` auto-updates; pin to a date string in production.
- Output files arrive as `file_id` in `code_execution_output` blocks.
- Anthropic Skills use short IDs + date versions; custom Skills use `skill_01...` + epoch versions.

## Uploading custom Skills

```python
from pathlib import Path

skill = client.beta.skills.create(
    display_title="Sales reports",
    files=[("SKILL.md", Path("./my_skill/SKILL.md").read_bytes(), "text/markdown")],
    betas=["skills-2025-10-02"],
)

response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    betas=["code-execution-2025-08-25", "skills-2025-10-02", "files-api-2025-04-14"],
    container={"skills": [
        {"type": "custom",    "skill_id": skill.id, "version": "latest"},
        {"type": "anthropic", "skill_id": "xlsx",   "version": "latest"},
    ]},
    tools=[{"type": "code_execution_20250825", "name": "code_execution"}],
    messages=[{"role": "user", "content": "Generate Q3 sales report"}],
)
```

Gotchas:
- Uploaded API Skills are **workspace-wide**; claude.ai custom Skills are per-user (not admin-managed).
- Both `type` and `skill_id` are required; `version` defaults to `"latest"`.
- List endpoint: `source="anthropic"` or `"custom"` to filter.
- The filesystem lives in the code-execution container — ephemeral by default; reuse `container.id` to persist.

## Authoring best practices

Principles:
- **Concise** — assume Claude is smart; push heavy content into bundled files (`REFERENCE.md`, `CHARTS.md`) that are read only when needed.
- **Match freedom level** — high-freedom guidance for creative tasks, template scripts for repetitive ones, exact command blocks for rigid ones.
- **Write the description in third person** ("Processes..."), not "I/You". Gerund-form names (`processing-pdfs`).
- **Ship scripts** — their code never enters context, only their output does.
- Target **< 5k tokens** in SKILL.md body.
- Test across Opus / Sonnet / Haiku before rollout.

Anti-patterns:
- Vague names (`helper`, `utils`).
- Descriptions that say only WHAT, not WHEN — Claude uses the description to decide whether to load the Skill at all.
- Bundled scripts with network calls, credentials, or untrusted dependencies.

## Enterprise governance

**Risk indicators (high):** executable scripts, instruction manipulation, MCP references, network calls, hardcoded creds, MCP-like tool discovery patterns.
**Medium:** broad filesystem scope, tool invocations.

Pin versions in production:
```python
container = {"skills": [{"type": "custom",
    "skill_id": "skill_01AbCdEf...",
    "version": "1759178010641129"}]}
```

Process:
- Separate authors from reviewers.
- Require an evaluation suite (3-5 queries per Skill: triggered + not-triggered + edge cases) before prod.
- Keep Git as source of truth — Skills don't sync across surfaces.
- Usage analytics aren't in the API — instrument client-side.
- Have a rollback plan: keep the previous version published.

## Sources

- `../docs/skills/agent-skills-overview.md`
- `../docs/skills/get-started.md`
- `../docs/skills/authoring-best-practices.md`
- `../docs/skills/skills-for-enterprise.md`
- `../docs/skills/using-skills-with-the-api.md`
