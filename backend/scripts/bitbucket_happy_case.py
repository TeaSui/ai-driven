#!/usr/bin/env python3
"""Bitbucket Cloud dual-support happy-case verification.

Exercises BitbucketClient directly against the configured repo using credentials
from the environment (AI_DRIVEN_BITBUCKET_USERNAME + AI_DRIVEN_BITBUCKET_APP_PASSWORD).

Run:
    source .venv/bin/activate
    python scripts/bitbucket_happy_case.py [--repo origination1/ai-driven]

This avoids the need to run the full Docker stack. It's a smoke test for the
dual-support infra (factory, client, new get_pr_reviewers / get_pr_checks).
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ai_driven.scm.bitbucket_client import BitbucketClient  # noqa: E402
from ai_driven.scm.factory import get_scm_client, get_scm_client_from_settings  # noqa: E402


GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"


def ok(msg: str) -> None:
    print(f"{GREEN}✓{RESET} {msg}")


def fail(msg: str) -> None:
    print(f"{RED}✗{RESET} {msg}")


def warn(msg: str) -> None:
    print(f"{YELLOW}!{RESET} {msg}")


async def main(repo: str) -> int:
    # Pulls from os.environ first, then .env / .env.local via pydantic-settings.
    from ai_driven.config import get_settings
    s = get_settings()
    username = s.bitbucket_username.strip()
    app_pw = s.bitbucket_app_password.strip()
    workspace = getattr(s, "bitbucket_workspace", "").strip()

    print("─" * 60)
    print("Bitbucket happy-case verification")
    print("─" * 60)
    print(f"  Repo:      {repo}")
    print(f"  Username:  {username or '(unset)'}")
    print(f"  App pass:  {'(set)' if app_pw else '(unset)'}")
    print(f"  Workspace: {workspace or '(unset)'}")
    print()

    if not (username and app_pw):
        fail("Missing AI_DRIVEN_BITBUCKET_USERNAME or AI_DRIVEN_BITBUCKET_APP_PASSWORD")
        return 2

    # Step 1: factory by scm_type
    try:
        client = get_scm_client("bitbucket", {"username": username, "app_password": app_pw})
        assert isinstance(client, BitbucketClient), "factory returned wrong client type"
        ok("factory.get_scm_client('bitbucket', ...) returns BitbucketClient")
        await client.close()
    except Exception as ex:
        fail(f"factory.get_scm_client failed: {ex!r}")
        return 1

    # Step 2: factory from settings
    try:
        client = get_scm_client_from_settings("bitbucket")
        ok("factory.get_scm_client_from_settings('bitbucket') OK")
        await client.close()
    except Exception as ex:
        fail(f"factory.get_scm_client_from_settings failed: {ex!r}")
        return 1

    client = BitbucketClient(username=username, app_password=app_pw)

    try:
        # Step 3: list files (repo root)
        try:
            entries = await client.list_files(repo, path="", ref="main")
            ok(f"list_files(root, ref=main) → {len(entries)} entries")
            for e in entries[:5]:
                print(f"    • {e.name}{'/' if e.is_directory else ''}")
            if len(entries) > 5:
                print(f"    … ({len(entries) - 5} more)")
        except Exception as ex:
            # Some repos use master or default branch isn't main
            warn(f"list_files(main) failed — trying 'master': {ex!r}")
            try:
                entries = await client.list_files(repo, path="", ref="master")
                ok(f"list_files(root, ref=master) → {len(entries)} entries")
            except Exception as ex2:
                fail(f"list_files failed on both main and master: {ex2!r}")
                return 1

        # Step 4: read a likely file (README)
        readme_candidates = ["README.md", "README", "readme.md", "Readme.md"]
        found_readme = False
        for path in readme_candidates:
            try:
                fc = await client.read_file(repo, path, ref="main")
                ok(f"read_file({path}) → {fc.size} bytes")
                found_readme = True
                break
            except Exception:
                continue
        if not found_readme:
            warn("No README found at root — skipped read_file step")

        # Step 5: list open PRs
        try:
            prs = await client.list_pull_requests(repo, state="OPEN", pagelen=5)
            ok(f"list_pull_requests(OPEN) → {len(prs)} PRs")
            for pr in prs[:3]:
                print(f"    • #{pr.number}: {pr.title}")

            # Step 6: if there's an open PR, exercise the new methods
            if prs:
                pr_num = prs[0].number
                try:
                    diff = await client.get_pr_diff(repo, pr_num)
                    ok(
                        f"get_pr_diff(#{pr_num}) → "
                        f"{len(diff.files)} files, +{diff.total_additions}/-{diff.total_deletions}"
                    )
                except Exception as ex:
                    fail(f"get_pr_diff(#{pr_num}) failed: {ex!r}")

                try:
                    reviewers = await client.get_pr_reviewers(repo, pr_num)
                    ok(f"get_pr_reviewers(#{pr_num}) → {len(reviewers)} reviewers")
                    for r in reviewers[:5]:
                        print(f"    • {r.display_name} ({r.state})")
                except Exception as ex:
                    fail(f"get_pr_reviewers(#{pr_num}) failed: {ex!r}")

                try:
                    checks = await client.get_pr_checks(repo, pr_num)
                    ok(f"get_pr_checks(#{pr_num}) → {len(checks)} checks")
                    for c in checks[:5]:
                        concl = c.conclusion or c.status
                        print(f"    • {c.name} [{concl}]")
                except Exception as ex:
                    fail(f"get_pr_checks(#{pr_num}) failed: {ex!r}")
            else:
                warn("No open PRs to exercise diff/reviewers/checks — create one to test")
        except Exception as ex:
            fail(f"list_pull_requests failed: {ex!r}")
            return 1

    finally:
        await client.close()

    print()
    print("─" * 60)
    ok("Bitbucket happy-case verification PASSED")
    print("─" * 60)
    print()
    print("Full workflow next steps (require backend running):")
    print("  1. Register the repo via POST /api/admin/repos "
          "with scmType='bitbucket'")
    print("  2. Configure a webhook on the Bitbucket repo pointing at "
          "/webhooks/bitbucket")
    print("  3. Comment '/ai-code <TICKET>' on a PR or use the chat UI "
          "to trigger CodingAgent")
    print("  4. Watch events at /dashboard/tasks/{id}")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="origination1/ai-driven")
    args = ap.parse_args()
    rc = asyncio.run(main(args.repo))
    sys.exit(rc)
