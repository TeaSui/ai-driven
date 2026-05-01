"""T27 — Phase 2 dogfood harness.

Submits a YAML corpus of tickets through ``POST /api/chat/send``,
polls ``GET /api/admin/tasks/{task_id}`` until each one reaches a
terminal status, and writes per-ticket JSONL results to
``--output``. Terminal statuses: ``completed``, ``failed``,
``blocked``, ``qa_failed``.

Auth
----
The poll path uses admin routes (``/api/admin/...``) which require an
admin session-token or team-level X-API-Key. For simplicity this
harness sends both headers — the server picks whichever matches the
middleware path. The recommended runbook uses a dedicated dogfood
team with a higher concurrent-cap.

429 handling
------------
``/api/chat/send`` is rate-limited to 10/min/team and has a concurrent
cap. With ``--parallel 3`` + a 15s poll cycle the steady-state rate
is well under the limit, but bursts can trip it. On 429 we exponential-
backoff up to 2 minutes and retry; we do NOT treat 429 as terminal.

Budget cap
----------
``--budget-cap-usd`` stops NEW submissions at the threshold; in-flight
tickets complete normally. Worst-case overshoot is
``parallel * max_ticket_cost``.

PR cleanup
----------
On exit the harness iterates every ``pr_number`` the corpus produced
and calls GitHub's ``PATCH /repos/{owner}/{repo}/pulls/{n}`` with
``{"state": "closed"}``. Needs ``GITHUB_TOKEN`` with PR-write scope on
the dogfood repo. If the env var is missing we log a counter and skip
cleanup — the caller can close PRs manually.

CLI
---
    python -m scripts.phase2_dogfood_harness \
        --corpus scripts/phase2_dogfood_corpus.yaml \
        --api-url http://localhost:8888 \
        --api-key $AI_DRIVEN_API_KEY \
        --output docs/phase2-dogfood-results-raw.jsonl \
        --parallel 3 \
        --max-wait-min 30 \
        --budget-cap-usd 150 \
        [--session-token $DOGFOOD_SESSION] \
        [--dry-run]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import random
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx
import yaml

logger = logging.getLogger("phase2_dogfood_harness")


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

TERMINAL_STATUSES: frozenset[str] = frozenset(
    {"completed", "failed", "blocked", "qa_failed"}
)
DEFAULT_POLL_INTERVAL_S = 15
# Upper bound for the 429 exponential backoff. Two minutes covers a
# worst-case concurrent-cap release; beyond that, keep retrying at the
# cap rather than giving up — the harness is long-running anyway.
MAX_BACKOFF_S = 120
DEFAULT_MAX_WAIT_MIN = 30
DEFAULT_PARALLEL = 3
DEFAULT_BUDGET_CAP = 150.0


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class CorpusTicket:
    """One entry from the YAML corpus."""

    id: str
    tier_expected: str
    message: str
    repo: str = "MinnMinn/skills-introduction-to-github"
    expected_outcome: str | None = None


@dataclass
class TicketResult:
    """Per-ticket outcome. Serialized as JSONL."""

    id: str
    tier_expected: str
    repo: str
    expected_outcome: str | None = None
    task_id: str | None = None
    status: str | None = None
    cost_usd: float = 0.0
    per_role_cost: dict[str, float] = field(default_factory=dict)
    per_role_verdict: dict[str, str] = field(default_factory=dict)
    iterations: int | None = None
    pr_number: int | None = None
    qa_verdict: str | None = None
    halted_on: str | None = None
    blocking_reasons: list[str] = field(default_factory=list)
    classifier_tier: str | None = None
    duration_seconds: float = 0.0
    overrides: list[dict[str, Any]] = field(default_factory=list)
    outcome_match: bool | None = None
    error: str | None = None


# ---------------------------------------------------------------------------
# Corpus loading
# ---------------------------------------------------------------------------


def load_corpus(path: Path) -> list[CorpusTicket]:
    """Load a YAML corpus and validate required fields."""
    with path.open() as f:
        raw = yaml.safe_load(f)
    if not isinstance(raw, list):
        raise ValueError(f"Corpus {path} must be a YAML list of tickets")

    tickets: list[CorpusTicket] = []
    for i, entry in enumerate(raw):
        if not isinstance(entry, dict):
            raise ValueError(f"Corpus entry #{i} is not a mapping")
        missing = [k for k in ("id", "tier_expected", "message") if k not in entry]
        if missing:
            raise ValueError(
                f"Corpus entry #{i} (id={entry.get('id')!r}) missing keys: {missing}"
            )
        tickets.append(
            CorpusTicket(
                id=str(entry["id"]),
                tier_expected=str(entry["tier_expected"]),
                message=str(entry["message"]),
                repo=str(entry.get("repo", "MinnMinn/skills-introduction-to-github")),
                expected_outcome=entry.get("expected_outcome"),
            )
        )
    return tickets


# ---------------------------------------------------------------------------
# HTTP client helpers
# ---------------------------------------------------------------------------


def _auth_headers(api_key: str | None, session_token: str | None) -> dict[str, str]:
    """Build auth headers.

    The admin routes require either a session-token cookie/header OR a
    team-level X-API-Key that belongs to a member with admin role. We
    send whichever is provided — the server picks the right path.
    """
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if api_key:
        headers["X-API-Key"] = api_key
    if session_token:
        headers["Authorization"] = f"Bearer {session_token}"
    return headers


async def _request_with_429_backoff(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    headers: dict[str, str],
    json_body: dict[str, Any] | None = None,
    max_backoff_s: int = MAX_BACKOFF_S,
) -> httpx.Response:
    """Send an HTTP request, honoring 429 with exponential backoff.

    429 is NOT treated as terminal — every other 4xx/5xx is returned
    as-is for the caller to handle. Backoff doubles each attempt
    (1, 2, 4, 8, 16, 32, 64, 120...) capped at ``max_backoff_s``,
    with ±20% jitter to avoid thundering herd when multiple workers
    are throttled simultaneously.
    """
    attempt = 0
    while True:
        resp = await client.request(method, url, headers=headers, json=json_body)
        if resp.status_code != 429:
            return resp

        # Honor Retry-After if present; otherwise exponential.
        retry_after = resp.headers.get("Retry-After")
        if retry_after:
            try:
                delay = float(retry_after)
            except ValueError:
                delay = min(2 ** attempt, max_backoff_s)
        else:
            delay = min(2 ** attempt, max_backoff_s)
        # Jitter ±20%.
        delay = max(1.0, delay * (0.8 + 0.4 * random.random()))
        logger.info(
            "429 on %s %s — backing off %.1fs (attempt %d)",
            method, url, delay, attempt + 1,
        )
        await asyncio.sleep(delay)
        attempt += 1


# ---------------------------------------------------------------------------
# Submit + poll
# ---------------------------------------------------------------------------


async def submit_ticket(
    client: httpx.AsyncClient,
    api_url: str,
    ticket: CorpusTicket,
    headers: dict[str, str],
) -> str | None:
    """Submit one ticket via ``POST /api/chat/send``.

    Returns the task_id on success, or None on a non-retryable error.
    """
    body = {
        "message": ticket.message,
        "agentType": "workflow",
        "context": {
            "repo_full_name": ticket.repo,
            "corpus_id": ticket.id,
            "tier_expected": ticket.tier_expected,
        },
    }
    try:
        resp = await _request_with_429_backoff(
            client, "POST", f"{api_url}/api/chat/send",
            headers=headers, json_body=body,
        )
    except Exception as ex:
        logger.warning("submit_failed id=%s err=%s", ticket.id, ex)
        return None

    if resp.status_code >= 400:
        logger.warning(
            "submit_failed id=%s status=%s body=%s",
            ticket.id, resp.status_code, resp.text[:200],
        )
        return None

    data = resp.json()
    task_id = data.get("taskId") or data.get("task_id")
    if not task_id:
        logger.warning("submit_no_task_id id=%s body=%s", ticket.id, data)
        return None
    return task_id


async def poll_until_terminal(
    client: httpx.AsyncClient,
    api_url: str,
    task_id: str,
    headers: dict[str, str],
    *,
    max_wait_s: float,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
) -> tuple[dict[str, Any] | None, str]:
    """Poll the admin task endpoint until terminal or timeout.

    Returns ``(task_json, verdict)``. ``verdict`` is the final status
    if terminal, ``"stuck"`` on timeout, ``"error"`` on a non-recoverable
    HTTP failure.
    """
    deadline = time.monotonic() + max_wait_s
    task_json: dict[str, Any] | None = None

    while time.monotonic() < deadline:
        try:
            resp = await _request_with_429_backoff(
                client, "GET", f"{api_url}/api/admin/tasks/{task_id}",
                headers=headers,
            )
        except Exception as ex:
            logger.warning("poll_exception task=%s err=%s", task_id, ex)
            await asyncio.sleep(poll_interval_s)
            continue

        if resp.status_code >= 500:
            logger.warning(
                "poll_5xx task=%s status=%s", task_id, resp.status_code
            )
            await asyncio.sleep(poll_interval_s)
            continue
        if resp.status_code >= 400:
            logger.error(
                "poll_4xx task=%s status=%s body=%s",
                task_id, resp.status_code, resp.text[:200],
            )
            return None, "error"

        task_json = resp.json()
        status = task_json.get("status")
        if status in TERMINAL_STATUSES:
            return task_json, status or "unknown"
        await asyncio.sleep(poll_interval_s)

    return task_json, "stuck"


async def fetch_artifacts(
    client: httpx.AsyncClient,
    api_url: str,
    task_id: str,
    headers: dict[str, str],
) -> dict[str, Any] | None:
    """Fetch ``/api/admin/tasks/{id}/artifacts`` (T25). Returns the
    decoded ``data`` object or None on failure — a missing artifacts
    payload is not fatal, we just get an empty per-role breakdown."""
    try:
        resp = await _request_with_429_backoff(
            client, "GET", f"{api_url}/api/admin/tasks/{task_id}/artifacts",
            headers=headers,
        )
    except Exception as ex:
        logger.warning("artifacts_exception task=%s err=%s", task_id, ex)
        return None
    if resp.status_code != 200:
        return None
    return resp.json().get("data")


async def fetch_override_audit(
    client: httpx.AsyncClient,
    api_url: str,
    task_id: str,
    headers: dict[str, str],
) -> list[dict[str, Any]]:
    """Fetch ``/api/admin/override-audit?task_id=X`` (T27). Returns the
    list of audit rows (possibly empty) or [] on failure."""
    try:
        resp = await _request_with_429_backoff(
            client, "GET",
            f"{api_url}/api/admin/override-audit?task_id={task_id}",
            headers=headers,
        )
    except Exception as ex:
        logger.warning("audit_exception task=%s err=%s", task_id, ex)
        return []
    if resp.status_code != 200:
        return []
    return resp.json().get("data", []) or []


# ---------------------------------------------------------------------------
# Per-ticket worker
# ---------------------------------------------------------------------------


def _compute_outcome_match(
    expected: str | None, actual_status: str | None
) -> bool | None:
    """Match rules:
      * expected 'blocked' → actual in {'blocked', 'qa_failed'}
      * expected 'completed' → actual == 'completed'
      * no expected → None (not evaluated)
    """
    if expected is None:
        return None
    if expected == "blocked":
        return actual_status in {"blocked", "qa_failed"}
    if expected == "completed":
        return actual_status == "completed"
    return None


def _extract_per_role(artifacts_data: dict[str, Any] | None) -> tuple[
    dict[str, float], dict[str, str]
]:
    """Return (per_role_cost, per_role_verdict) from the T25 artifacts
    payload. cost_usd is a string in the payload (Decimal) — we cast to
    float for aggregation; the harness report uses medians/means which
    don't care about sub-cent precision."""
    per_cost: dict[str, float] = {}
    per_verdict: dict[str, str] = {}
    if not artifacts_data:
        return per_cost, per_verdict
    for a in artifacts_data.get("artifacts", []) or []:
        role = a.get("role")
        if not role:
            continue
        try:
            per_cost[role] = per_cost.get(role, 0.0) + float(a.get("costUsd", 0) or 0)
        except (TypeError, ValueError):
            pass
        # Last verdict wins — in practice there is one artifact per role.
        per_verdict[role] = a.get("verdict", "")
    return per_cost, per_verdict


async def run_ticket(
    client: httpx.AsyncClient,
    api_url: str,
    ticket: CorpusTicket,
    headers: dict[str, str],
    *,
    max_wait_s: float,
    poll_interval_s: float,
) -> TicketResult:
    """Submit + poll + enrich one ticket. Catches all exceptions — a
    single-ticket failure must not crash the harness."""
    result = TicketResult(
        id=ticket.id,
        tier_expected=ticket.tier_expected,
        repo=ticket.repo,
        expected_outcome=ticket.expected_outcome,
    )
    start = time.monotonic()
    try:
        task_id = await submit_ticket(client, api_url, ticket, headers)
        if not task_id:
            result.error = "submit_failed"
            return result
        result.task_id = task_id

        task_json, verdict = await poll_until_terminal(
            client, api_url, task_id, headers,
            max_wait_s=max_wait_s, poll_interval_s=poll_interval_s,
        )
        result.status = verdict
        if task_json:
            result.cost_usd = float(task_json.get("costUsd") or 0) or 0.0
            result.iterations = task_json.get("iterations")
            output = task_json.get("output") or task_json.get("outputData") or {}
            result.pr_number = (
                task_json.get("prNumber")
                or (output.get("pr_number") if isinstance(output, dict) else None)
            )
            if not result.pr_number and isinstance(output, dict):
                result.halted_on = output.get("orchestrator_halted_on")
                result.blocking_reasons = output.get("blocking_reasons") or []
                result.classifier_tier = output.get("classifier_tier")
                result.qa_verdict = output.get("qa_verdict")

        # Enrich with artifacts + audit — only if task actually got an id.
        artifacts = await fetch_artifacts(client, api_url, task_id, headers)
        if artifacts:
            result.classifier_tier = (
                result.classifier_tier or artifacts.get("classifierTier")
            )
            result.qa_verdict = result.qa_verdict or artifacts.get("qaVerdict")
            per_cost, per_verdict = _extract_per_role(artifacts)
            result.per_role_cost = per_cost
            result.per_role_verdict = per_verdict

        result.overrides = await fetch_override_audit(
            client, api_url, task_id, headers
        )

    except Exception as ex:
        # Defensive: any bug in enrichment shouldn't kill the ticket's
        # result — we keep what we have.
        logger.exception("run_ticket_unexpected id=%s err=%s", ticket.id, ex)
        result.error = f"{type(ex).__name__}: {ex}"
    finally:
        result.duration_seconds = time.monotonic() - start
        result.outcome_match = _compute_outcome_match(
            ticket.expected_outcome, result.status
        )
    return result


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------


async def run_corpus(
    *,
    tickets: list[CorpusTicket],
    api_url: str,
    api_key: str | None,
    session_token: str | None,
    output_path: Path,
    parallel: int,
    max_wait_min: int,
    budget_cap_usd: float,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
) -> list[TicketResult]:
    """Run the full corpus with bounded parallelism + budget cap.

    Writes each terminal ticket's result to ``output_path`` as JSONL so
    a crash mid-run still leaves the completed rows on disk.
    """
    headers = _auth_headers(api_key, session_token)
    max_wait_s = max_wait_min * 60.0
    results: list[TicketResult] = []
    spent_usd = 0.0
    sem = asyncio.Semaphore(parallel)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    # Open in append mode — multiple dogfood runs append to the same
    # file; the report consumer can filter by timestamp if needed.
    out_fp = output_path.open("a")
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:

            async def _worker(t: CorpusTicket) -> TicketResult:
                nonlocal spent_usd
                async with sem:
                    # Budget cap — check at acquisition time so we don't
                    # start NEW work once we crossed the threshold. In-
                    # flight tickets finish normally (they hold the sem
                    # already).
                    if spent_usd >= budget_cap_usd:
                        skip = TicketResult(
                            id=t.id, tier_expected=t.tier_expected,
                            repo=t.repo, expected_outcome=t.expected_outcome,
                            status="skipped_budget_cap",
                            error=f"budget cap {budget_cap_usd} reached ({spent_usd:.2f} spent)",
                        )
                        return skip
                    res = await run_ticket(
                        client, api_url, t, headers,
                        max_wait_s=max_wait_s, poll_interval_s=poll_interval_s,
                    )
                    spent_usd += res.cost_usd
                    return res

            tasks = [asyncio.create_task(_worker(t)) for t in tickets]
            for coro in asyncio.as_completed(tasks):
                res = await coro
                results.append(res)
                out_fp.write(json.dumps(_result_to_dict(res)) + "\n")
                out_fp.flush()
                logger.info(
                    "ticket_done id=%s status=%s cost=%.4f spent=%.2f/%.2f",
                    res.id, res.status, res.cost_usd, spent_usd, budget_cap_usd,
                )
    finally:
        out_fp.close()

    return results


def _result_to_dict(r: TicketResult) -> dict[str, Any]:
    """Serialize a TicketResult for JSONL output."""
    return {
        "id": r.id,
        "tier_expected": r.tier_expected,
        "repo": r.repo,
        "expected_outcome": r.expected_outcome,
        "task_id": r.task_id,
        "status": r.status,
        "cost_usd": r.cost_usd,
        "per_role_cost": r.per_role_cost,
        "per_role_verdict": r.per_role_verdict,
        "iterations": r.iterations,
        "pr_number": r.pr_number,
        "qa_verdict": r.qa_verdict,
        "halted_on": r.halted_on,
        "blocking_reasons": r.blocking_reasons,
        "classifier_tier": r.classifier_tier,
        "duration_seconds": r.duration_seconds,
        "overrides": r.overrides,
        "outcome_match": r.outcome_match,
        "error": r.error,
    }


# ---------------------------------------------------------------------------
# PR cleanup
# ---------------------------------------------------------------------------


async def close_prs(
    results: list[TicketResult], github_token: str | None
) -> dict[str, int]:
    """Close every PR this run opened. Returns a counter dict.

    Skips gracefully if ``github_token`` is not set — the runbook says
    to close them manually in that case.
    """
    counts = {"attempted": 0, "closed": 0, "failed": 0, "skipped_no_token": 0}
    if not github_token:
        counts["skipped_no_token"] = sum(1 for r in results if r.pr_number)
        logger.warning(
            "GITHUB_TOKEN not set — skipping PR cleanup for %d PRs",
            counts["skipped_no_token"],
        )
        return counts

    async with httpx.AsyncClient(timeout=30.0) as client:
        for r in results:
            if not r.pr_number:
                continue
            counts["attempted"] += 1
            url = f"https://api.github.com/repos/{r.repo}/pulls/{r.pr_number}"
            try:
                resp = await client.patch(
                    url,
                    headers={
                        "Authorization": f"Bearer {github_token}",
                        "Accept": "application/vnd.github+json",
                        "X-GitHub-Api-Version": "2022-11-28",
                    },
                    json={"state": "closed"},
                )
                if resp.status_code < 300:
                    counts["closed"] += 1
                else:
                    counts["failed"] += 1
                    logger.warning(
                        "pr_close_failed repo=%s pr=%d status=%s",
                        r.repo, r.pr_number, resp.status_code,
                    )
            except Exception as ex:
                counts["failed"] += 1
                logger.warning(
                    "pr_close_exception repo=%s pr=%d err=%s",
                    r.repo, r.pr_number, ex,
                )
    logger.info("pr_cleanup counts=%s", counts)
    return counts


# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------


def render_dry_run(tickets: list[CorpusTicket]) -> str:
    """Human-readable dump of the corpus without hitting the network."""
    from collections import Counter
    tiers = Counter(t.tier_expected for t in tickets)
    blocked = sum(1 for t in tickets if t.expected_outcome == "blocked")
    lines = [
        "Dogfood corpus dry-run",
        f"  total tickets: {len(tickets)}",
        f"  tier breakdown: {dict(tiers)}",
        f"  intentional-block: {blocked}",
        "",
        "tickets:",
    ]
    for t in tickets:
        suffix = f" [expected={t.expected_outcome}]" if t.expected_outcome else ""
        lines.append(f"  - {t.id} ({t.tier_expected}){suffix}: {t.message[:80]}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_argparser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Phase 2 dogfood harness — submit corpus + poll + write JSONL"
    )
    p.add_argument("--corpus", type=Path, required=True,
                   help="Path to the YAML corpus file")
    p.add_argument("--api-url", type=str, default="http://localhost:8888",
                   help="Base URL of the ai-driven API (no trailing slash)")
    p.add_argument("--api-key", type=str, default=None,
                   help="Team X-API-Key (or use --session-token)")
    p.add_argument("--session-token", type=str, default=None,
                   help="Admin session token (Authorization: Bearer ...)")
    p.add_argument("--output", type=Path,
                   default=Path("docs/phase2-dogfood-results-raw.jsonl"),
                   help="JSONL output path (appended)")
    p.add_argument("--parallel", type=int, default=DEFAULT_PARALLEL,
                   help="Max concurrent in-flight tickets (default 3)")
    p.add_argument("--max-wait-min", type=int, default=DEFAULT_MAX_WAIT_MIN,
                   help="Per-ticket timeout in minutes (default 30)")
    p.add_argument("--budget-cap-usd", type=float, default=DEFAULT_BUDGET_CAP,
                   help="Stop NEW submissions after this much spend")
    p.add_argument("--poll-interval-s", type=float, default=DEFAULT_POLL_INTERVAL_S,
                   help="Poll cadence in seconds (default 15)")
    p.add_argument("--dry-run", action="store_true",
                   help="Print the corpus and exit without submitting")
    p.add_argument("--log-level", type=str, default="INFO")
    return p


async def _async_main(args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    tickets = load_corpus(args.corpus)

    if args.dry_run:
        print(render_dry_run(tickets))
        return 0

    if not args.api_key and not args.session_token:
        logger.error(
            "Must provide --api-key or --session-token (admin routes required)"
        )
        return 2

    logger.info(
        "Starting dogfood run — %d tickets, parallel=%d, cap=$%.2f",
        len(tickets), args.parallel, args.budget_cap_usd,
    )

    results = await run_corpus(
        tickets=tickets,
        api_url=args.api_url.rstrip("/"),
        api_key=args.api_key,
        session_token=args.session_token,
        output_path=args.output,
        parallel=args.parallel,
        max_wait_min=args.max_wait_min,
        budget_cap_usd=args.budget_cap_usd,
        poll_interval_s=args.poll_interval_s,
    )

    logger.info(
        "Corpus complete — %d results written to %s",
        len(results), args.output,
    )

    # PR cleanup is best-effort — never fails the run.
    await close_prs(results, os.environ.get("GITHUB_TOKEN"))

    return 0


def main() -> int:  # pragma: no cover — CLI entrypoint
    args = build_argparser().parse_args()
    return asyncio.run(_async_main(args))


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
