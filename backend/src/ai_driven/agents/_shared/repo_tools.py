"""Shared read-only repo-inspection helpers used by CodingAgent and any
specialist (StackAnalyst, QA) that needs to peek at a target repository
without triggering write side-effects.

These helpers back the ``repo.list_files``, ``repo.read_file``, and
``repo.overview`` tool shapes. They are factored out of the coding agent
so that sibling modules can depend on a public name instead of
re-importing a private underscore-prefixed helper.
"""

from __future__ import annotations

import asyncio
import fnmatch
from typing import Any

# Cost/latency bounds on recursive listings. A 50-iteration agent loop that
# recursively walks depth=10 on a large monorepo would cost multiple $s in
# Claude context alone; cap aggressively and let the agent narrow with
# `path` or `pattern` if it needs more.
_MAX_RECURSIVE_DEPTH = 5
_MAX_FILES_PER_LIST_CALL = 500
_MAX_BATCH_READS = 20
_READ_TRUNCATE_BYTES = 10240

# Top-level manifest files `repo.overview` surfaces so the agent can see the
# tech stack at a glance without a second tool call. Kept small; files that
# live inside subdirs (e.g. go.sum) are skipped to stay compact.
_MANIFEST_FILES = (
    "package.json", "pyproject.toml", "requirements.txt", "setup.py",
    "go.mod", "Cargo.toml", "pom.xml", "build.gradle", "build.gradle.kts",
    "Gemfile", "composer.json", "tsconfig.json",
    "README.md", "README.rst",
)


async def list_files_batched(
    scm: Any,
    repo_full_name: str,
    paths: list[str],
    *,
    recursive: bool,
    max_depth: int,
    pattern: str | None,
    ref: str,
) -> list[dict[str, Any]]:
    """Run list_files over N paths (optionally recursively) and merge results.

    Caller sees one flat list ordered by insertion. Duplicates are removed
    by path so overlapping `paths` don't double-report. Capped at
    _MAX_FILES_PER_LIST_CALL entries total so a recursive walk over a huge
    repo can't blow up the context window.
    """
    depth_cap = min(max(max_depth, 1), _MAX_RECURSIVE_DEPTH)
    seen: set[str] = set()
    out: list[dict[str, Any]] = []

    async def _walk(start: str, depth_left: int) -> None:
        if len(out) >= _MAX_FILES_PER_LIST_CALL:
            return
        try:
            entries = await scm.list_files(repo_full_name, start, ref)
        except Exception as exc:  # noqa: BLE001
            out.append({"path": start, "error": f"list_files failed: {exc}"})
            return
        subdirs: list[str] = []
        for e in entries:
            if e.path in seen:
                continue
            seen.add(e.path)
            if pattern and not fnmatch.fnmatch(e.path, pattern):
                # Still recurse into directories that don't match so we can
                # find nested matches, but don't emit them.
                if e.is_directory and recursive and depth_left > 1:
                    subdirs.append(e.path)
                continue
            out.append({
                "path": e.path,
                "name": e.name,
                "is_directory": e.is_directory,
                "size": e.size,
            })
            if len(out) >= _MAX_FILES_PER_LIST_CALL:
                return
            if e.is_directory and recursive and depth_left > 1:
                subdirs.append(e.path)
        for sub in subdirs:
            if len(out) >= _MAX_FILES_PER_LIST_CALL:
                return
            await _walk(sub, depth_left - 1)

    for start in paths:
        if not recursive:
            # Single-level listing per path — cheaper and matches the old
            # tool's shape when recursive is off.
            try:
                entries = await scm.list_files(repo_full_name, start, ref)
            except Exception as exc:  # noqa: BLE001
                out.append({"path": start, "error": f"list_files failed: {exc}"})
                continue
            for e in entries:
                if e.path in seen:
                    continue
                seen.add(e.path)
                if pattern and not fnmatch.fnmatch(e.path, pattern):
                    continue
                out.append({
                    "path": e.path,
                    "name": e.name,
                    "is_directory": e.is_directory,
                    "size": e.size,
                })
                if len(out) >= _MAX_FILES_PER_LIST_CALL:
                    break
            if len(out) >= _MAX_FILES_PER_LIST_CALL:
                break
        else:
            await _walk(start, depth_cap)

    if len(out) >= _MAX_FILES_PER_LIST_CALL:
        out.append({
            "_truncated": True,
            "reason": f"hit {_MAX_FILES_PER_LIST_CALL}-entry cap; narrow `path` or add a `pattern`",
        })
    return out


async def read_files_batched(
    scm: Any, repo_full_name: str, paths: list[str], ref: str,
) -> list[dict[str, Any]]:
    """Fan out reads in parallel, truncate each to _READ_TRUNCATE_BYTES.

    Bounded at _MAX_BATCH_READS to keep single-request token usage sane;
    excess paths return an error entry.
    """
    capped = paths[:_MAX_BATCH_READS]
    extra = [
        {"path": p, "error": f"batch too large; >{_MAX_BATCH_READS} files. "
                              "Split across multiple calls."}
        for p in paths[_MAX_BATCH_READS:]
    ]

    async def _one(p: str) -> dict[str, Any]:
        try:
            f = await scm.read_file(repo_full_name, p, ref)
        except Exception as exc:  # noqa: BLE001
            return {"path": p, "error": f"read_file failed: {exc}"}
        content = f.content
        if f.size > _READ_TRUNCATE_BYTES:
            content = (
                content[:_READ_TRUNCATE_BYTES]
                + f"\n\n[... truncated, {f.size} bytes total]"
            )
        return {"path": f.path, "content": content, "size": f.size}

    return list(await asyncio.gather(*(_one(p) for p in capped))) + extra


async def build_repo_overview(
    scm: Any, repo_full_name: str, max_depth: int, ref: str,
) -> dict[str, Any]:
    """Return {tree: [...], manifests: {path: content}} for a quick whole-repo snapshot.

    The tree is a compact list of dicts (not a formatted string) so the
    agent can parse it programmatically. Manifests are truncated like
    regular reads. Capped at the same limits as list_files_batched.
    """
    depth_cap = min(max(max_depth, 1), _MAX_RECURSIVE_DEPTH)
    tree = await list_files_batched(
        scm, repo_full_name, paths=[""],
        recursive=True, max_depth=depth_cap, pattern=None, ref=ref,
    )
    # Pick out manifest files that appeared in the tree.
    manifest_paths = [
        e["path"] for e in tree
        if not e.get("is_directory") and e.get("name") in _MANIFEST_FILES
    ]
    manifests = await read_files_batched(
        scm, repo_full_name, manifest_paths, ref,
    )
    return {
        "tree": tree,
        "manifests": {m["path"]: m.get("content", m.get("error", ""))
                      for m in manifests},
        "truncation_note": (
            f"tree depth capped at {depth_cap}, {_MAX_FILES_PER_LIST_CALL} entries max. "
            "Call repo.list_files with a deeper path if you need more detail."
        ),
    }
