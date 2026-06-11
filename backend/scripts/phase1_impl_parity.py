"""backend/scripts/phase1_impl_parity.py

One-shot parity smoke: send the same 3 requests through legacy and
langchain impls, diff the MessageResponse surface the rest of the
codebase consumes. NOT a unit test — requires real credentials; run
manually / in the shadow environment. Usage:

    AI_DRIVEN_CLAUDE_PROVIDER=anthropic python scripts/phase1_impl_parity.py
"""

from __future__ import annotations

import asyncio
import json
import os

CASES = [
    {"label": "plain",
     "messages": [{"role": "user", "content": "Reply with exactly: pong"}],
     "kwargs": {}},
    {"label": "system+temp",
     "messages": [{"role": "user", "content": "One word: colour of the sky at noon."}],
     "kwargs": {"system": "Answer in one lowercase word.", "temperature": 0.0}},
    {"label": "tool_use",
     "messages": [{"role": "user", "content": "Look up ticket KAN-1 using the tool."}],
     "kwargs": {"tools": [{"name": "jira.get_issue", "description": "Fetch a Jira issue by key.",
                           "input_schema": {"type": "object",
                                            "properties": {"key": {"type": "string"}},
                                            "required": ["key"]}}]}},
]


async def run(impl: str) -> list[dict]:
    from ai_driven.config import get_settings
    get_settings.cache_clear()
    os.environ["AI_DRIVEN_CLAUDE_PROVIDER_IMPL"] = impl
    get_settings.cache_clear()

    from ai_driven.claude.provider import create_provider
    provider = create_provider(resilient=False)
    settings = get_settings()
    out = []
    for case in CASES:
        r = await provider.create_message(
            model=settings.claude_default_model, max_tokens=256,
            messages=[dict(m) for m in case["messages"]], **case["kwargs"],
        )
        out.append({
            "label": case["label"],
            "stop_reason": r.stop_reason,
            "has_tool_use": r.has_tool_use,
            "tool_names": [b.name for b in r.tool_use_blocks],
            "usage_nonzero": r.usage.input_tokens > 0 and r.usage.output_tokens > 0,
            "text_nonempty": bool(r.text.strip()) or r.has_tool_use,
        })
    return out


async def main() -> None:
    legacy = await run("legacy")
    langchain = await run("langchain")
    failures = []
    for a, b in zip(legacy, langchain):
        for key in ("stop_reason", "has_tool_use", "tool_names", "usage_nonzero", "text_nonempty"):
            if a[key] != b[key]:
                failures.append(f"{a['label']}.{key}: legacy={a[key]!r} langchain={b[key]!r}")
    print(json.dumps({"legacy": legacy, "langchain": langchain}, indent=2))
    if failures:
        raise SystemExit("PARITY FAIL:\n" + "\n".join(failures))
    print("PARITY OK (3/3 cases)")


if __name__ == "__main__":
    asyncio.run(main())
