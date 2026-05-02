"""T27 unit tests for scripts/phase2_dogfood_harness.py.

Covers the discipline points from the brief:
  * poll loop exits on terminal status
  * poll loop surfaces stuck when max_wait is exceeded
  * 429 is NOT treated as terminal — backoff + retry
  * budget cap blocks NEW submissions (in-flight finish)
  * JSONL output shape is stable
  * corpus loader validates required fields

HTTP is mocked via httpx's MockTransport so we exercise the real
AsyncClient code path (auth headers, retries, timeouts) without a
server.
"""

from __future__ import annotations

import asyncio
import json
import tempfile
from pathlib import Path
from typing import Any, Callable
from unittest.mock import AsyncMock

import httpx
import pytest

from scripts.phase2_dogfood_harness import (
    CorpusTicket,
    TicketResult,
    _auth_headers,
    _compute_outcome_match,
    _extract_per_role,
    _request_with_429_backoff,
    close_prs,
    load_corpus,
    poll_until_terminal,
    render_dry_run,
    run_corpus,
    submit_ticket,
)


# ---------------------------------------------------------------------------
# load_corpus
# ---------------------------------------------------------------------------


def _write_yaml(tmp_path: Path, content: str) -> Path:
    p = tmp_path / "corpus.yaml"
    p.write_text(content)
    return p


def test_load_corpus_parses_required_fields(tmp_path: Path):
    p = _write_yaml(tmp_path, """
- id: fast-1
  tier_expected: fast_path
  message: "Fix typo"
- id: block-1
  tier_expected: full_workflow
  expected_outcome: blocked
  repo: org/alt-repo
  message: "Store raw card numbers"
""")
    tickets = load_corpus(p)
    assert len(tickets) == 2
    assert tickets[0].id == "fast-1"
    assert tickets[0].repo == "MinnMinn/skills-introduction-to-github"
    assert tickets[0].expected_outcome is None
    assert tickets[1].repo == "org/alt-repo"
    assert tickets[1].expected_outcome == "blocked"


def test_load_corpus_rejects_missing_fields(tmp_path: Path):
    p = _write_yaml(tmp_path, """
- id: bad-1
  message: "missing tier"
""")
    with pytest.raises(ValueError, match="missing keys"):
        load_corpus(p)


def test_load_corpus_rejects_non_list(tmp_path: Path):
    p = _write_yaml(tmp_path, "id: not-a-list\n")
    with pytest.raises(ValueError, match="YAML list"):
        load_corpus(p)


# ---------------------------------------------------------------------------
# _auth_headers
# ---------------------------------------------------------------------------


def test_auth_headers_includes_both_when_present():
    h = _auth_headers("abc", "token")
    assert h["X-API-Key"] == "abc"
    assert h["Authorization"] == "Bearer token"
    assert h["Content-Type"] == "application/json"


def test_auth_headers_api_key_only():
    h = _auth_headers("abc", None)
    assert h["X-API-Key"] == "abc"
    assert "Authorization" not in h


def test_auth_headers_neither():
    h = _auth_headers(None, None)
    assert "X-API-Key" not in h
    assert "Authorization" not in h


# ---------------------------------------------------------------------------
# _compute_outcome_match
# ---------------------------------------------------------------------------


def test_outcome_match_blocked_vs_blocked():
    assert _compute_outcome_match("blocked", "blocked") is True


def test_outcome_match_blocked_vs_qa_failed_also_true():
    """qa_failed is a post-coding block — still counts as 'blocked' match."""
    assert _compute_outcome_match("blocked", "qa_failed") is True


def test_outcome_match_blocked_vs_completed_is_regression():
    assert _compute_outcome_match("blocked", "completed") is False


def test_outcome_match_none_returns_none():
    assert _compute_outcome_match(None, "completed") is None


# ---------------------------------------------------------------------------
# _extract_per_role
# ---------------------------------------------------------------------------


def test_extract_per_role_aggregates_and_casts_cost():
    data = {
        "artifacts": [
            {"role": "security", "verdict": "pass", "costUsd": "0.001234"},
            {"role": "qa", "verdict": "block", "costUsd": "0.002500"},
            {"role": "security", "verdict": "pass_with_notes", "costUsd": "0.001000"},
        ]
    }
    costs, verdicts = _extract_per_role(data)
    # security cost = 0.001234 + 0.001000
    assert costs["security"] == pytest.approx(0.002234)
    assert costs["qa"] == pytest.approx(0.0025)
    # last verdict wins for security
    assert verdicts["security"] == "pass_with_notes"
    assert verdicts["qa"] == "block"


def test_extract_per_role_handles_none():
    assert _extract_per_role(None) == ({}, {})


# ---------------------------------------------------------------------------
# 429 backoff
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_429_retry_then_success(monkeypatch):
    """First call returns 429, second returns 200. Must not give up."""
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"Retry-After": "0"})
        return httpx.Response(200, json={"ok": True})

    # Skip the jitter sleep in the backoff path.
    async def _no_sleep(_s):
        return None
    monkeypatch.setattr("asyncio.sleep", _no_sleep)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        resp = await _request_with_429_backoff(
            client, "GET", "http://test/x", headers={}
        )
    assert resp.status_code == 200
    assert calls["n"] == 2


@pytest.mark.asyncio
async def test_429_is_not_terminal(monkeypatch):
    """Every call returns 429. Harness must keep retrying (up to
    per-ticket timeout, controlled at a higher level). Here we just
    verify the backoff loop does not bail early."""
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 5:
            return httpx.Response(429)
        return httpx.Response(200, json={"ok": True})

    async def _no_sleep(_s):
        return None
    monkeypatch.setattr("asyncio.sleep", _no_sleep)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        resp = await _request_with_429_backoff(
            client, "GET", "http://test/x", headers={}
        )
    assert resp.status_code == 200
    assert calls["n"] == 5


@pytest.mark.asyncio
async def test_500_is_not_retried_by_backoff_helper(monkeypatch):
    """Only 429 triggers retry in this helper — 500 is returned as-is
    so the caller can decide. (Poll loop does its own 500 retry.)"""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        resp = await _request_with_429_backoff(
            client, "GET", "http://test/x", headers={}
        )
    assert resp.status_code == 500


# ---------------------------------------------------------------------------
# submit_ticket
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_submit_ticket_returns_task_id(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        body = json.loads(request.content)
        assert body["message"].startswith("Fix")
        assert body["context"]["corpus_id"] == "fast-1"
        return httpx.Response(200, json={
            "taskId": "abc-123", "sessionId": "s", "status": "queued",
            "message": "ok",
        })

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        task_id = await submit_ticket(
            client, "http://test",
            CorpusTicket(id="fast-1", tier_expected="fast_path",
                         message="Fix typo"),
            headers={},
        )
    assert task_id == "abc-123"


@pytest.mark.asyncio
async def test_submit_ticket_returns_none_on_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"error": "bad"})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        task_id = await submit_ticket(
            client, "http://test",
            CorpusTicket(id="x", tier_expected="fast_path", message="x"),
            headers={},
        )
    assert task_id is None


# ---------------------------------------------------------------------------
# poll_until_terminal
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_poll_exits_on_terminal_status(monkeypatch):
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 3:
            return httpx.Response(200, json={"status": "running"})
        return httpx.Response(200, json={"status": "completed", "costUsd": 0.1})

    async def _no_sleep(_s):
        return None
    monkeypatch.setattr("asyncio.sleep", _no_sleep)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        body, verdict = await poll_until_terminal(
            client, "http://test", "task-1", headers={},
            max_wait_s=60, poll_interval_s=0.001,
        )
    assert verdict == "completed"
    assert body and body["status"] == "completed"
    assert calls["n"] == 3


@pytest.mark.asyncio
async def test_poll_returns_stuck_on_timeout(monkeypatch):
    """A task that never reaches terminal should return ('stuck',
    last-seen-body). max_wait_s of 0 forces the deadline to be in the
    past immediately."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "running"})

    async def _no_sleep(_s):
        return None
    monkeypatch.setattr("asyncio.sleep", _no_sleep)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        body, verdict = await poll_until_terminal(
            client, "http://test", "task-1", headers={},
            max_wait_s=0, poll_interval_s=0.001,
        )
    assert verdict == "stuck"


@pytest.mark.asyncio
async def test_poll_exits_on_each_terminal_flavor(monkeypatch):
    for status in ("completed", "failed", "blocked", "qa_failed"):
        def handler(request: httpx.Request, s=status) -> httpx.Response:
            return httpx.Response(200, json={"status": s})

        async def _no_sleep(_s):
            return None
        monkeypatch.setattr("asyncio.sleep", _no_sleep)

        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as client:
            body, verdict = await poll_until_terminal(
                client, "http://test", "task-1", headers={},
                max_wait_s=5, poll_interval_s=0.001,
            )
        assert verdict == status, f"expected {status} but got {verdict}"


# ---------------------------------------------------------------------------
# run_corpus — integration-ish, budget cap + bounded parallel
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_run_corpus_respects_budget_cap(tmp_path, monkeypatch):
    """Once cumulative spend crosses the cap, later tickets must be
    skipped with status=skipped_budget_cap.

    We stub run_ticket to return a fixed cost so the math is obvious.
    """
    import scripts.phase2_dogfood_harness as h

    call_order: list[str] = []

    async def _fake_run_ticket(client, api_url, ticket, headers, **kw):
        call_order.append(ticket.id)
        return TicketResult(
            id=ticket.id, tier_expected=ticket.tier_expected,
            repo=ticket.repo, status="completed",
            cost_usd=1.0,
        )

    monkeypatch.setattr(h, "run_ticket", _fake_run_ticket)

    tickets = [
        CorpusTicket(id=f"t-{i}", tier_expected="fast_path", message="x")
        for i in range(5)
    ]
    out = tmp_path / "results.jsonl"

    results = await run_corpus(
        tickets=tickets,
        api_url="http://test",
        api_key="k",
        session_token=None,
        output_path=out,
        parallel=1,
        max_wait_min=1,
        budget_cap_usd=2.5,  # allow 2 full $1 tickets; 3rd trips the cap
        poll_interval_s=0.001,
    )

    statuses = [r.status for r in results]
    completed = [r for r in results if r.status == "completed"]
    skipped = [r for r in results if r.status == "skipped_budget_cap"]
    # At parallel=1, we process strictly serially. After 2 completions
    # (spent=$2), the 3rd acquisition sees spent >= cap ($2.5) —
    # wait, $2 < $2.5. So 3rd runs (spent becomes $3), 4th+ skipped.
    assert len(completed) == 3
    assert len(skipped) == 2
    # JSONL file has all rows
    with out.open() as f:
        lines = [json.loads(ln) for ln in f if ln.strip()]
    assert len(lines) == 5


@pytest.mark.asyncio
async def test_run_corpus_writes_stable_jsonl_shape(tmp_path, monkeypatch):
    """Every emitted row must carry the full set of expected keys so
    the report generator can trust the schema."""
    import scripts.phase2_dogfood_harness as h

    async def _fake_run_ticket(client, api_url, ticket, headers, **kw):
        return TicketResult(
            id=ticket.id, tier_expected=ticket.tier_expected,
            repo=ticket.repo, status="completed",
            cost_usd=0.1,
            per_role_cost={"security": 0.05},
            per_role_verdict={"security": "pass"},
            classifier_tier="fast_path",
            qa_verdict="pass",
            pr_number=42,
        )

    monkeypatch.setattr(h, "run_ticket", _fake_run_ticket)

    tickets = [CorpusTicket(id="t-1", tier_expected="fast_path", message="x")]
    out = tmp_path / "r.jsonl"
    await run_corpus(
        tickets=tickets, api_url="http://test",
        api_key="k", session_token=None, output_path=out,
        parallel=1, max_wait_min=1, budget_cap_usd=100,
        poll_interval_s=0.001,
    )
    with out.open() as f:
        row = json.loads(f.read().strip())

    expected_keys = {
        "id", "tier_expected", "repo", "expected_outcome",
        "task_id", "status", "cost_usd", "per_role_cost",
        "per_role_verdict", "iterations", "pr_number",
        "qa_verdict", "halted_on", "blocking_reasons",
        "classifier_tier", "duration_seconds", "overrides",
        "outcome_match", "error",
    }
    assert set(row.keys()) == expected_keys
    assert row["status"] == "completed"
    assert row["classifier_tier"] == "fast_path"


@pytest.mark.asyncio
async def test_run_corpus_bounded_by_parallel(tmp_path, monkeypatch):
    """At most `parallel` tickets should be in-flight at once."""
    import scripts.phase2_dogfood_harness as h

    in_flight = 0
    max_in_flight = 0
    lock = asyncio.Lock()

    async def _fake_run_ticket(client, api_url, ticket, headers, **kw):
        nonlocal in_flight, max_in_flight
        async with lock:
            in_flight += 1
            max_in_flight = max(max_in_flight, in_flight)
        # Yield so other tasks can enter
        await asyncio.sleep(0.01)
        async with lock:
            in_flight -= 1
        return TicketResult(
            id=ticket.id, tier_expected=ticket.tier_expected,
            repo=ticket.repo, status="completed", cost_usd=0.01,
        )

    monkeypatch.setattr(h, "run_ticket", _fake_run_ticket)

    tickets = [
        CorpusTicket(id=f"t-{i}", tier_expected="fast_path", message="x")
        for i in range(10)
    ]
    out = tmp_path / "r.jsonl"
    await run_corpus(
        tickets=tickets, api_url="http://test",
        api_key="k", session_token=None, output_path=out,
        parallel=3, max_wait_min=1, budget_cap_usd=100,
        poll_interval_s=0.001,
    )
    assert max_in_flight <= 3


# ---------------------------------------------------------------------------
# PR cleanup
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_close_prs_skips_when_no_token():
    results = [
        TicketResult(id="t-1", tier_expected="fast_path",
                     repo="org/r", pr_number=42, status="completed"),
        TicketResult(id="t-2", tier_expected="fast_path",
                     repo="org/r", pr_number=43, status="completed"),
        TicketResult(id="t-3", tier_expected="fast_path",
                     repo="org/r", pr_number=None, status="failed"),
    ]
    counts = await close_prs(results, None)
    assert counts["skipped_no_token"] == 2
    assert counts["attempted"] == 0
    assert counts["closed"] == 0


# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------


def test_render_dry_run_shows_totals_and_ids():
    tickets = [
        CorpusTicket(id="fast-1", tier_expected="fast_path", message="a"),
        CorpusTicket(id="full-1", tier_expected="full_workflow", message="b"),
        CorpusTicket(id="block-1", tier_expected="full_workflow",
                     message="c", expected_outcome="blocked"),
    ]
    out = render_dry_run(tickets)
    assert "total tickets: 3" in out
    assert "intentional-block: 1" in out
    assert "fast-1" in out and "block-1" in out
    assert "expected=blocked" in out
