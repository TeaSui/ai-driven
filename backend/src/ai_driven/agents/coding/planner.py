"""Planning phase — uses Claude with extended thinking to create an implementation plan."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.thinking import resolve_thinking_config
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class ImplementationPlan:
    """Structured plan from the planning phase."""

    summary: str = ""
    files_to_create: list[dict[str, str]] = field(default_factory=list)
    files_to_modify: list[dict[str, str]] = field(default_factory=list)
    test_strategy: str = ""
    risks: list[str] = field(default_factory=list)
    steps: list[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ImplementationPlan:
        return cls(
            summary=data.get("summary", ""),
            files_to_create=data.get("files_to_create", []),
            files_to_modify=data.get("files_to_modify", []),
            test_strategy=data.get("test_strategy", ""),
            risks=data.get("risks", []),
            steps=data.get("steps", []),
        )


PLANNING_SYSTEM_PROMPT = """You are a senior software architect planning an implementation.

Given a Jira ticket, repository structure, and relevant code, create a detailed implementation plan.

You MUST respond with a JSON object in this exact format:
```json
{
  "summary": "Brief description of the implementation approach",
  "files_to_create": [{"path": "src/...", "purpose": "..."}],
  "files_to_modify": [{"path": "src/...", "changes": "Description of changes needed"}],
  "test_strategy": "How to test the implementation",
  "risks": ["Risk 1", "Risk 2"],
  "steps": ["Step 1: ...", "Step 2: ...", "Step 3: ..."]
}
```

Consider:
- Existing code patterns and conventions
- Backward compatibility
- Test coverage
- Error handling
- Security implications
"""


class PlanningAgent:
    """Uses Claude with extended thinking to create an implementation plan."""

    def __init__(self, claude_client: ClaudeClient | None = None) -> None:
        self._claude = claude_client or ClaudeClient()

    async def create_plan(
        self,
        jira_summary: str,
        jira_description: str,
        acceptance_criteria: str,
        repo_structure: list[str],
        relevant_code: list[dict[str, str]],
    ) -> ImplementationPlan:
        """Create an implementation plan using extended thinking.

        Extended thinking is INCOMPATIBLE with assistant prefill: when
        thinking is enabled the first content block must be a `thinking`
        block, so we cannot force JSON shape via the prefill + stop_sequences
        pattern used elsewhere. We tighten the prompt to forbid prose
        wrappers instead, and on parse failure the agent loudly logs the
        raw text so schema drift surfaces — see _parse_plan.
        """
        settings = get_settings()

        prompt = self._build_prompt(
            jira_summary,
            jira_description,
            acceptance_criteria,
            repo_structure,
            relevant_code,
        )

        response = await self._claude.chat(
            messages=[{"role": "user", "content": prompt}],
            model=settings.claude_planning_model,
            thinking=resolve_thinking_config(settings.claude_planning_model, budget_tokens=16000),
        )

        return self._parse_plan(response.text)

    def _build_prompt(
        self,
        summary: str,
        description: str,
        acceptance_criteria: str,
        repo_structure: list[str],
        relevant_code: list[dict[str, str]],
    ) -> str:
        code_sections = ""
        for item in relevant_code[:10]:
            code_sections += (
                f"\n### {item['path']}\n```\n{item['content'][:3000]}\n```\n"
            )

        structure = "\n".join(repo_structure[:200])

        return f"""## Jira Ticket

**Summary:** {summary}

**Description:**
{description}

**Acceptance Criteria:**
{acceptance_criteria}

## Repository Structure
```
{structure}
```

## Relevant Code
{code_sections}

Output a single ImplementationPlan JSON object inside one ```json fenced
block. No prose before or after. Required keys: summary, files_to_modify,
files_to_create, test_strategy, risks. Do NOT invent additional top-level
keys.
"""

    @staticmethod
    def _parse_plan(text: str) -> ImplementationPlan:
        """Parse the plan JSON from Claude's response.

        Silent truncation on parse failure hid upstream schema drift. Now
        we log the FULL raw response at WARNING so the ops pipeline
        surfaces the bad shape, and still degrade gracefully so callers
        don't crash mid-run.
        """
        cleaned = text.strip()
        if "```json" in cleaned:
            start = cleaned.index("```json") + 7
            end = cleaned.index("```", start)
            cleaned = cleaned[start:end].strip()
        elif "```" in cleaned:
            start = cleaned.index("```") + 3
            end = cleaned.index("```", start)
            cleaned = cleaned[start:end].strip()

        try:
            data = json.loads(cleaned)
            return ImplementationPlan.from_dict(data)
        except (json.JSONDecodeError, ValueError) as e:
            logger.warning(
                "PlanningAgent: failed to parse plan JSON (%s). "
                "Full raw response follows so schema drift is visible in logs "
                "instead of being silently truncated to summary[:500]:\n%s",
                e, text,
            )
            return ImplementationPlan(summary=text[:500])
