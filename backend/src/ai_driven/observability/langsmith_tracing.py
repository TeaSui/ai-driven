"""LangSmith tracing — Phase 0 of docs/design/langgraph-migration.md.

Inert unless ``settings.langsmith_enabled`` is True AND ``LANGSMITH_API_KEY``
is set in the environment. When enabled, payload masking is applied per
``settings.langsmith_mask_payloads`` (default True): message/system/tool
content is redacted while permitted metadata (model name, token counts,
stop reason, sampling params, timing) survives — which is what cost/latency
analysis needs.

Security rules implemented here (docs/security/langsmith-tracing.md):

* **LSMITH-01** — masking ON by default; constructing a tracer with masking
  OFF in ``production``/``staging`` is refused (fail-secure).
* **LSMITH-02** — Phase 0 does NOT trace streaming; only ``create_message``
  is decorated (see claude/provider.py). No span on the streaming path means
  no unmasked streaming egress.
* **LSMITH-03** — masking is allowlist / deny-by-default: only the permitted
  keys leave; any unrecognized key (incl. a future PII-carrying kwarg) is
  dropped. Non-string content is redacted, never passed through.
* **LSMITH-04** — only permitted metadata is retained.
* **LSMITH-05/06** — the API key is read by the langsmith SDK directly from
  the env; this module never reads, stores, or logs it.
* **LSMITH-12** — masking fails SECURE: ``mask_inputs``/``mask_outputs`` never
  raise and never return raw content; on any internal error they return a
  fully-redacted placeholder. (The langsmith SDK falls back to the *raw*
  inputs if a ``process_inputs`` callable raises — verified in
  ``langsmith/run_helpers.py`` — so the masker must be total.)
* **LSMITH-13** — tracing fails OPEN: any langsmith error (import, wrapping,
  export) must not change the wrapped call's behavior, signature, or return
  value. When tracing is inactive the decorator is a pure pass-through.
"""

from __future__ import annotations

import functools
import logging
import os
from typing import Any, Callable

from ai_driven.config import Environment, Settings, get_settings

logger = logging.getLogger(__name__)

# The ONLY input keys permitted to leave when masking is on (LSMITH-03/04, §2
# of the threat model). Everything else is deny-by-default.
_PERMITTED_INPUT_KEYS = frozenset(
    {"model", "max_tokens", "temperature", "top_p"}
)

_MASKING_ERROR_PLACEHOLDER: dict[str, Any] = {
    "__masking_error__": "<redacted:masking failed>"
}


def mask_inputs(inputs: dict[str, Any]) -> dict[str, Any]:
    """Allowlist (deny-by-default) redaction of call inputs (LSMITH-03/04).

    Emits ONLY the permitted-metadata allowlist plus redaction *summaries*
    for ``system``, ``messages``, and ``tools``. Any key not handled here is
    dropped, so a future kwarg cannot leak by default. Never raises and never
    returns raw content: on any internal error it returns a fully-redacted
    placeholder so the langsmith SDK cannot fall back to the raw payload
    (LSMITH-12, fail-secure).
    """
    try:
        masked: dict[str, Any] = {}

        if not isinstance(inputs, dict):
            return dict(_MASKING_ERROR_PLACEHOLDER)

        # 1. Permitted scalar metadata — copied through only when present.
        for key in _PERMITTED_INPUT_KEYS:
            if key in inputs:
                masked[key] = inputs[key]

        # 2. System prompt → length summary only.
        system = inputs.get("system")
        if isinstance(system, str):
            masked["system"] = f"<redacted:{len(system)}chars>"
        elif system is not None:
            masked["system"] = "<redacted>"

        # 3. Messages → keep role, redact ALL content (string or not).
        messages = inputs.get("messages")
        if isinstance(messages, list):
            redacted_messages = []
            for m in messages:
                if isinstance(m, dict):
                    redacted_messages.append(
                        {"role": m.get("role", "?"), "content": "<redacted>"}
                    )
                else:
                    redacted_messages.append({"role": "?", "content": "<redacted>"})
            masked["messages"] = redacted_messages
        elif messages is not None:
            # Non-list messages payload — redact wholesale, never pass through.
            masked["messages"] = "<redacted>"

        # 4. Tools → count summary only.
        tools = inputs.get("tools")
        if isinstance(tools, list):
            masked["tools"] = f"<redacted:{len(tools)} tools>"
        elif tools is not None:
            masked["tools"] = "<redacted>"

        return masked
    except BaseException:  # noqa: BLE001 - fail secure, never leak raw content
        # Do NOT log the payload. Counter-style log only.
        logger.warning("langsmith mask_inputs failed; dropping payload (fail-secure)")
        return dict(_MASKING_ERROR_PLACEHOLDER)


def mask_outputs(response: Any) -> dict[str, Any]:
    """Keep ``stop_reason``/``model``/``usage``; drop content blocks.

    Never raises and never returns content (LSMITH-12, fail-secure).
    """
    try:
        usage = getattr(response, "usage", None)
        return {
            "stop_reason": getattr(response, "stop_reason", None),
            "model": getattr(response, "model", None),
            "usage": {
                "input_tokens": getattr(usage, "input_tokens", 0),
                "output_tokens": getattr(usage, "output_tokens", 0),
            },
        }
    except BaseException:  # noqa: BLE001 - fail secure
        logger.warning("langsmith mask_outputs failed; dropping payload (fail-secure)")
        return dict(_MASKING_ERROR_PLACEHOLDER)


class PermittedMetadataTracer:
    """Validates the enablement/masking posture at construction (LSMITH-01).

    Refuses to be constructed when tracing is on but masking is off in
    ``production`` or ``staging`` — masking is non-bypassable there.
    """

    _MASKING_REQUIRED_ENVS = frozenset({Environment.PRODUCTION, Environment.STAGING})

    def __init__(self, settings: Settings) -> None:
        if (
            settings.langsmith_enabled
            and not settings.langsmith_mask_payloads
            and settings.environment in self._MASKING_REQUIRED_ENVS
        ):
            raise ValueError(
                "LangSmith payload masking cannot be disabled in "
                f"{settings.environment.value!r}: tracing-on + masking-off is "
                "refused (LSMITH-01, fail-secure)."
            )
        self._settings = settings

    @property
    def masking_on(self) -> bool:
        return self._settings.langsmith_mask_payloads


def _tracing_active() -> bool:
    """True only when explicitly enabled AND an API key is present in the env.

    The API key is read here ONLY to gate activation; it is never stored or
    logged (LSMITH-05/06). The langsmith SDK reads it independently.
    """
    return get_settings().langsmith_enabled and bool(os.environ.get("LANGSMITH_API_KEY"))


def _get_traceable() -> Callable[..., Any]:
    """Lazily import langsmith.traceable (indirection enables test patching)."""
    from langsmith import traceable

    return traceable


def traced_llm(name: str) -> Callable:
    """Decorator: LangSmith ``@traceable(run_type="llm")`` when active, else no-op.

    The active/inactive decision is made PER CALL (not at import) so tests and
    runtime flag flips behave predictably. Any failure in the tracing setup or
    export fails OPEN — the wrapped call still runs and returns unchanged
    (LSMITH-13).
    """

    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> Any:
            if not _tracing_active():
                return await func(*args, **kwargs)
            try:
                settings = get_settings()
                # LSMITH-01 posture check on the live settings.
                PermittedMetadataTracer(settings)
                traceable = _get_traceable()
                use_mask = settings.langsmith_mask_payloads
                traced = traceable(
                    run_type="llm",
                    name=name,
                    project_name=settings.langsmith_project,
                    process_inputs=mask_inputs if use_mask else None,
                    process_outputs=mask_outputs if use_mask else None,
                )(func)
                return await traced(*args, **kwargs)
            except BaseException:  # noqa: BLE001 - tracing must never break the call
                # Fail OPEN: never let a tracing error change call behavior.
                # No payload/key in the log.
                logger.warning(
                    "langsmith tracing failed for %s; proceeding without trace "
                    "(fail-open)",
                    name,
                )
                return await func(*args, **kwargs)

        return wrapper

    return decorator
