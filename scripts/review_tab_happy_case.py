#!/usr/bin/env python3
"""Code Review tab — 5-case happy-path smoke test.

Exercises the admin endpoints that drive every pixel on the /dashboard/reviews
page, using a running backend + real SCM credentials.

The five cases:
    1. GET /api/admin/reviews              — review queue list
    2. GET /api/admin/reviews/{id}         — single review detail (summary bar)
    3. GET /api/admin/reviews/{id}/reviewers  — sidebar Reviewers (live SCM)
    4. GET /api/admin/reviews/{id}/checks     — sidebar Checks (live SCM)
    5. GET /api/admin/reviews/{id}/commits    — Commits tab (live SCM)

Run:
    source .venv/bin/activate
    # Optional: override the backend location or API key
    export AI_DRIVEN_SMOKE_BASE_URL=http://localhost:8888
    export AI_DRIVEN_SMOKE_API_KEY=test_key_playwright
    python scripts/review_tab_happy_case.py

Exits 0 on all-pass, 1 on any failure.

NOTE: this talks to a running backend. If the backend is not up or has no
reviews persisted, cases 2-5 skip with a clear message rather than hard-failing
— so you can run it in dev without seeding data.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
DIM = "\033[2m"
RESET = "\033[0m"


def ok(msg: str) -> None:
    print(f"{GREEN}✓{RESET} {msg}")


def fail(msg: str) -> None:
    print(f"{RED}✗{RESET} {msg}")


def warn(msg: str) -> None:
    print(f"{YELLOW}!{RESET} {msg}")


def info(msg: str) -> None:
    print(f"  {DIM}{msg}{RESET}")


class CaseFailed(Exception):
    """Raised to fail a single case without aborting the whole run."""


def _get(url: str, api_key: str) -> Any:
    # When api_key is empty, omit the header entirely — the backend's debug-mode
    # bypass only engages when X-API-Key is absent, not when it's present-but-bogus.
    headers: dict[str, str] = {"Accept": "application/json"}
    if api_key:
        headers["X-API-Key"] = api_key
    req = Request(url, headers=headers)
    try:
        with urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else None
    except HTTPError as ex:
        raise CaseFailed(f"HTTP {ex.code}: {ex.reason} — {url}") from ex
    except URLError as ex:
        raise CaseFailed(f"connection failed: {ex.reason} — {url}") from ex


def _seed_review() -> str:
    """Insert a deterministic Review + Task + Team row via the repo pattern.

    Used by --seed flag so a dev loop can exercise cases 2-5 without needing to
    trigger a real AI review first. Idempotent via deterministic UUIDs.
    """
    import asyncio
    import uuid
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
    from ai_driven.db.postgres.session import async_session_factory  # noqa: E402
    from ai_driven.db.postgres.models import Review, Task, Team  # noqa: E402
    from sqlalchemy import select  # noqa: E402
    from datetime import datetime, timedelta, timezone  # noqa: E402

    team_id = uuid.UUID("00000000-0000-0000-0000-000000000000")
    task_id = uuid.UUID("11111111-1111-1111-1111-111111111111")
    review_id = uuid.UUID("22222222-2222-2222-2222-222222222222")

    async def _do() -> str:
        async with async_session_factory() as session:
            # Team — should already exist (debug sentinel team created at boot).
            team = await session.get(Team, team_id)
            if team is None:
                raise RuntimeError(
                    "Debug sentinel team not found. Start the backend with "
                    "AI_DRIVEN_DEBUG=true at least once first."
                )

            # Task — idempotent upsert.
            task = await session.get(Task, task_id)
            now = datetime.now(timezone.utc)
            if task is None:
                task = Task(
                    id=task_id,
                    team_id=team_id,
                    agent_type="review",
                    title="Smoke seed: PR #1",
                    jira_key="SMOKE-1",
                    status="completed",
                    started_at=now - timedelta(seconds=42),
                    completed_at=now,
                )
                session.add(task)

            # Review — idempotent upsert.
            review = await session.get(Review, review_id)
            if review is None:
                review = Review(
                    id=review_id,
                    task_id=task_id,
                    pr_url="https://github.com/example/repo/pull/1",
                    pr_number=1,
                    repo_full_name="example/repo",
                    status="pending",
                    summary="Seeded review for smoke test",
                    comments_json=[
                        {
                            "file_path": "src/foo.py",
                            "line_number": 10,
                            "severity": "warn",
                            "category": "safety",
                            "confidence": 0.9,
                            "title": "Missing input validation",
                            "body": "Consider validating input",
                            "resolved": False,
                        }
                    ],
                    issues_found=1,
                    critical_issues=0,
                )
                session.add(review)

            await session.commit()
            return str(review_id)

    return asyncio.run(_do())


def case_1_list_reviews(base: str, api_key: str) -> list[dict[str, Any]]:
    """Case 1: GET /api/admin/reviews — the queue list."""
    url = f"{base}/api/admin/reviews"
    data = _get(url, api_key)
    if not isinstance(data, list):
        raise CaseFailed(f"expected array, got {type(data).__name__}: {data!r}")
    ok(f"case 1/5: GET /api/admin/reviews → {len(data)} reviews")
    if data:
        info(f"first review id: {data[0].get('id')}")
        info(f"first review repo: {data[0].get('repo')} PR#{data[0].get('prNumber')}")
    return data


def case_2_get_review(base: str, api_key: str, review_id: str) -> dict[str, Any]:
    """Case 2: GET /api/admin/reviews/{id} — single review detail."""
    url = f"{base}/api/admin/reviews/{review_id}"
    data = _get(url, api_key)
    if not isinstance(data, dict):
        raise CaseFailed(f"expected object, got {type(data).__name__}")
    if data.get("id") != review_id:
        raise CaseFailed(f"id mismatch: got {data.get('id')!r} expected {review_id!r}")
    ok(f"case 2/5: GET /api/admin/reviews/{review_id[:8]}…")
    info(f"status={data.get('status')} files={len(data.get('files') or [])} durationMs={data.get('durationMs')}")
    return data


def case_3_reviewers(base: str, api_key: str, review_id: str) -> dict[str, Any]:
    """Case 3: GET /api/admin/reviews/{id}/reviewers — sidebar Reviewers."""
    url = f"{base}/api/admin/reviews/{review_id}/reviewers"
    data = _get(url, api_key)
    if not isinstance(data, dict) or "reviewers" not in data:
        raise CaseFailed(f"expected {{reviewers: [...]}}, got: {data!r}")
    reviewers = data["reviewers"]
    if not reviewers:
        raise CaseFailed("expected at least the synthesised AI reviewer, got empty list")

    ai_present = any(r.get("username") == "gotyme-x-ai" for r in reviewers)
    if not ai_present:
        raise CaseFailed(
            "AI reviewer row missing — backend must always prepend the "
            "synthesised 'GoTyme-X AI' entry so the sidebar has >=1 row even "
            "when the PR has no human reviewers yet"
        )

    ok(f"case 3/5: /reviewers → {len(reviewers)} rows (AI reviewer present: {ai_present})")
    if data.get("scmError"):
        warn(f"  scmError reported: {data['scmError']}")
    for r in reviewers[:3]:
        info(f"  {r.get('displayName')} [{r.get('state')}]")
    return data


def case_4_checks(base: str, api_key: str, review_id: str) -> dict[str, Any]:
    """Case 4: GET /api/admin/reviews/{id}/checks — sidebar Checks."""
    url = f"{base}/api/admin/reviews/{review_id}/checks"
    data = _get(url, api_key)
    if not isinstance(data, dict) or "checks" not in data:
        raise CaseFailed(f"expected {{checks: [...]}}, got: {data!r}")
    ok(f"case 4/5: /checks → {len(data['checks'])} checks")
    if data.get("scmError"):
        warn(f"  scmError reported: {data['scmError']}")
    for c in data["checks"][:3]:
        info(f"  {c.get('name')} [{c.get('conclusion') or c.get('status')}]")
    return data


def case_5_commits(base: str, api_key: str, review_id: str) -> dict[str, Any]:
    """Case 5: GET /api/admin/reviews/{id}/commits — Commits tab."""
    url = f"{base}/api/admin/reviews/{review_id}/commits"
    data = _get(url, api_key)
    if not isinstance(data, dict) or "commits" not in data:
        raise CaseFailed(f"expected {{commits: [...]}}, got: {data!r}")
    ok(f"case 5/5: /commits → {len(data['commits'])} commits")
    if data.get("scmError"):
        warn(f"  scmError reported: {data['scmError']}")
    for c in data["commits"][:3]:
        info(f"  {c.get('shortSha')} {c.get('message', '')[:70]}")
    return data


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--base-url",
        default=os.environ.get("AI_DRIVEN_SMOKE_BASE_URL", "http://localhost:8888"),
    )
    ap.add_argument(
        "--api-key",
        default=os.environ.get("AI_DRIVEN_SMOKE_API_KEY", ""),
        help=(
            "API key for X-API-Key header. Defaults to empty so the "
            "backend's debug-mode bypass engages (backend only bypasses when "
            "the header is absent, not when it's present-but-bogus)."
        ),
    )
    ap.add_argument(
        "--seed",
        action="store_true",
        help=(
            "Before running cases 2-5, insert a throwaway Review row directly "
            "into Postgres so the cases have something to read. Safe to run "
            "repeatedly — uses a deterministic review id."
        ),
    )
    args = ap.parse_args()
    base = args.base_url.rstrip("/")

    print("─" * 60)
    print("Code Review tab smoke — 5 real cases")
    print("─" * 60)
    print(f"  Backend:   {base}")
    print(f"  API key:   {'(set)' if args.api_key else '(omitted — debug-mode bypass)'}")
    print()
    info(
        "If cases 3-5 return HTTP 404, the running backend pre-dates the "
        "/reviewers, /checks, /commits endpoints. Restart the backend "
        "(they're in admin/router.py) and re-run."
    )
    print()

    if args.seed:
        try:
            seeded = _seed_review()
            ok(f"seeded review id={seeded}")
        except Exception as ex:
            fail(f"seed failed: {ex}")
            return 1

    failures = 0
    skipped = 0

    # Case 1 — always runs. If backend is down or unauth, stop early.
    try:
        reviews = case_1_list_reviews(base, args.api_key)
    except CaseFailed as ex:
        fail(f"case 1/5: {ex}")
        print()
        fail("Aborting: case 1 is a precondition for cases 2-5.")
        return 1

    if not reviews:
        print()
        warn("No reviews persisted — cases 2-5 skipped.")
        warn("Seed a review (trigger an AI review, or POST a mock) and re-run.")
        skipped = 4
    else:
        review_id = reviews[0]["id"]
        for case_fn in (case_2_get_review, case_3_reviewers, case_4_checks, case_5_commits):
            try:
                case_fn(base, args.api_key, review_id)
            except CaseFailed as ex:
                fail(f"{case_fn.__name__}: {ex}")
                failures += 1

    print()
    print("─" * 60)
    if failures == 0 and skipped == 0:
        ok(f"Code Review tab smoke PASSED (5/5 cases)")
        return 0
    if failures == 0:
        warn(f"Code Review tab smoke PASSED with {skipped} skipped")
        return 0
    fail(f"Code Review tab smoke FAILED: {failures} failures, {skipped} skipped")
    return 1


if __name__ == "__main__":
    sys.exit(main())
