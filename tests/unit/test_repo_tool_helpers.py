"""Unit tests for the batched/recursive/glob/overview repo tool helpers.

These helpers back repo.list_files, repo.read_file, and repo.overview.
Tests drive them directly against a stub SCM client so we can assert
behaviour without spinning a real GitHub/Bitbucket API mock.
"""
from __future__ import annotations

from dataclasses import dataclass

import pytest

from ai_driven.agents.coding.agent import (
    _build_repo_overview,
    _list_files_batched,
    _read_files_batched,
)


@dataclass
class _Entry:
    path: str
    name: str
    is_directory: bool
    size: int = 0


@dataclass
class _File:
    path: str
    content: str
    size: int


class _StubSCM:
    """Minimal in-memory SCM that returns deterministic fixtures."""

    def __init__(self, tree: dict[str, list[_Entry]], files: dict[str, _File]):
        self._tree = tree
        self._files = files
        self.list_calls: list[tuple[str, str]] = []
        self.read_calls: list[str] = []

    async def list_files(self, repo: str, path: str, ref: str) -> list[_Entry]:
        self.list_calls.append((repo, path))
        return list(self._tree.get(path, []))

    async def read_file(self, repo: str, path: str, ref: str) -> _File:
        self.read_calls.append(path)
        if path in self._files:
            return self._files[path]
        raise FileNotFoundError(path)


def _fixture_scm() -> _StubSCM:
    tree = {
        "": [
            _Entry("src", "src", True),
            _Entry("tests", "tests", True),
            _Entry("package.json", "package.json", False, 200),
            _Entry("README.md", "README.md", False, 500),
        ],
        "src": [
            _Entry("src/app.py", "app.py", False, 800),
            _Entry("src/models", "models", True),
        ],
        "src/models": [
            _Entry("src/models/user.py", "user.py", False, 400),
            _Entry("src/models/order.py", "order.py", False, 600),
        ],
        "tests": [
            _Entry("tests/test_app.py", "test_app.py", False, 900),
        ],
    }
    files = {
        "package.json": _File("package.json", '{"name":"x"}', 200),
        "README.md": _File("README.md", "# X\n", 500),
        "src/app.py": _File("src/app.py", "print('hi')", 800),
        "src/models/user.py": _File("src/models/user.py", "class User: pass", 400),
        "src/models/order.py": _File("src/models/order.py", "class Order: pass", 600),
        "tests/test_app.py": _File("tests/test_app.py", "# tests", 900),
    }
    return _StubSCM(tree, files)


# --- list_files batched / recursive / glob ----------------------------------

@pytest.mark.asyncio
async def test_list_files_single_path_non_recursive() -> None:
    scm = _fixture_scm()
    out = await _list_files_batched(
        scm, "o/r", paths=["src"], recursive=False, max_depth=3,
        pattern=None, ref="main",
    )
    paths = {e["path"] for e in out if "path" in e}
    assert paths == {"src/app.py", "src/models"}
    # One call — non-recursive.
    assert scm.list_calls == [("o/r", "src")]


@pytest.mark.asyncio
async def test_list_files_multiple_paths_batched() -> None:
    scm = _fixture_scm()
    out = await _list_files_batched(
        scm, "o/r", paths=["src", "tests"], recursive=False, max_depth=3,
        pattern=None, ref="main",
    )
    paths = {e["path"] for e in out if "path" in e}
    assert "src/app.py" in paths
    assert "tests/test_app.py" in paths
    # Still one call per path, but a SINGLE tool invocation.
    assert len(scm.list_calls) == 2


@pytest.mark.asyncio
async def test_list_files_recursive_walks_tree() -> None:
    scm = _fixture_scm()
    out = await _list_files_batched(
        scm, "o/r", paths=[""], recursive=True, max_depth=3,
        pattern=None, ref="main",
    )
    paths = {e["path"] for e in out if "path" in e}
    assert {"src/app.py", "src/models/user.py", "src/models/order.py",
            "tests/test_app.py"}.issubset(paths)


@pytest.mark.asyncio
async def test_list_files_glob_pattern_filters_results() -> None:
    scm = _fixture_scm()
    out = await _list_files_batched(
        scm, "o/r", paths=[""], recursive=True, max_depth=3,
        pattern="*.py", ref="main",
    )
    paths = {e["path"] for e in out if "path" in e}
    # Only .py files match; package.json and README.md are filtered out.
    assert all(p.endswith(".py") for p in paths)
    assert "src/app.py" in paths
    assert "package.json" not in paths


@pytest.mark.asyncio
async def test_list_files_depth_cap_prevents_infinite_walk() -> None:
    # Build a deeply nested tree; with max_depth=1 we only see depth 1.
    tree = {
        "": [_Entry("a", "a", True)],
        "a": [_Entry("a/b", "b", True)],
        "a/b": [_Entry("a/b/c.py", "c.py", False, 1)],
    }
    scm = _StubSCM(tree, {})
    out = await _list_files_batched(
        scm, "o/r", paths=[""], recursive=True, max_depth=1,
        pattern=None, ref="main",
    )
    paths = {e["path"] for e in out if "path" in e}
    # Depth 1 sees "a"; depth 2+ entries ("a/b", "a/b/c.py") are NOT reached.
    assert paths == {"a"}


# --- read_files batched ------------------------------------------------------

@pytest.mark.asyncio
async def test_read_files_batched_returns_all_requested() -> None:
    scm = _fixture_scm()
    out = await _read_files_batched(
        scm, "o/r",
        ["package.json", "src/app.py", "src/models/user.py"], "main",
    )
    assert len(out) == 3
    assert out[0]["path"] == "package.json"
    assert out[1]["content"] == "print('hi')"
    assert out[2]["content"] == "class User: pass"


@pytest.mark.asyncio
async def test_read_files_batched_handles_missing_file() -> None:
    scm = _fixture_scm()
    out = await _read_files_batched(
        scm, "o/r", ["does/not/exist.py", "package.json"], "main",
    )
    assert len(out) == 2
    assert "error" in out[0]
    assert out[1]["content"] == '{"name":"x"}'


# --- repo.overview ----------------------------------------------------------

@pytest.mark.asyncio
async def test_repo_overview_returns_tree_and_manifests() -> None:
    scm = _fixture_scm()
    overview = await _build_repo_overview(scm, "o/r", max_depth=3, ref="main")
    assert "tree" in overview
    assert "manifests" in overview
    # Manifest files surfaced by name.
    assert "package.json" in overview["manifests"]
    assert "README.md" in overview["manifests"]
    # Tree includes nested files thanks to the recursive walk.
    tree_paths = {e["path"] for e in overview["tree"] if "path" in e}
    assert "src/models/user.py" in tree_paths
