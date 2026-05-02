"""In-memory SCM stub for reviewer eval cases.

Implements just enough of the SCM client surface that ReviewAgent uses:
get_pr_diff, read_file, list_files, search_code, post_pr_comment, approve_pr,
request_changes. Records every write call so graders can inspect what the
agent actually did.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ai_driven.scm.models import (
    DiffFile,
    DirectoryEntry,
    FileContent,
    PRComment,
    PRDiff,
)


@dataclass
class _PostedComment:
    repo_full_name: str
    pr_number: int
    body: str
    file_path: str | None
    line: int | None


@dataclass
class FakeSCM:
    """A non-network SCM client backed by a single eval case fixture.

    Construct from a parsed dataset JSON:
        FakeSCM.from_case(case_dict)
    """

    diff: PRDiff
    files: dict[str, str] = field(default_factory=dict)
    comments: list[_PostedComment] = field(default_factory=list)
    final_action: str | None = None  # "approved" | "changes_requested"
    final_action_body: str = ""

    @classmethod
    def from_case(cls, case: dict[str, Any]) -> "FakeSCM":
        diff_data = case.get("diff") or {}
        diff = PRDiff(
            files=[
                DiffFile(
                    path=f["path"],
                    status=f.get("status", "modified"),
                    additions=int(f.get("additions", 0)),
                    deletions=int(f.get("deletions", 0)),
                    patch=f.get("patch", ""),
                )
                for f in diff_data.get("files", [])
            ],
            total_additions=int(diff_data.get("total_additions", 0)),
            total_deletions=int(diff_data.get("total_deletions", 0)),
        )
        return cls(diff=diff, files=dict(case.get("extra_files") or {}))

    # --- read side -----------------------------------------------------

    async def get_pr_diff(self, repo_full_name: str, pr_number: int) -> PRDiff:
        return self.diff

    async def read_file(self, repo_full_name: str, path: str, ref: str = "main") -> FileContent:
        content = self.files.get(path, "")
        return FileContent(path=path, content=content, size=len(content))

    async def list_files(
        self, repo_full_name: str, path: str = "", ref: str = "main"
    ) -> list[DirectoryEntry]:
        prefix = path.rstrip("/")
        out: list[DirectoryEntry] = []
        for fp, content in self.files.items():
            if not prefix or fp.startswith(prefix + "/") or fp == prefix:
                name = fp.rsplit("/", 1)[-1]
                out.append(DirectoryEntry(path=fp, name=name, is_directory=False, size=len(content)))
        return out

    async def search_code(self, repo_full_name: str, query: str) -> list[FileContent]:
        out: list[FileContent] = []
        for path, content in self.files.items():
            if query.lower() in content.lower():
                out.append(FileContent(path=path, content=content[:2000], size=len(content)))
        return out

    # --- write side (recorded, not executed) ---------------------------

    async def post_pr_comment(
        self,
        repo_full_name: str,
        pr_number: int,
        body: str,
        file_path: str | None = None,
        line: int | None = None,
    ) -> PRComment:
        self.comments.append(
            _PostedComment(repo_full_name, pr_number, body, file_path, line)
        )
        return PRComment(
            id=f"fake-comment-{len(self.comments)}",
            body=body,
            author="fake-bot",
            file_path=file_path,
            line=line,
        )

    async def approve_pr(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        self.final_action = "approved"
        self.final_action_body = body

    async def request_changes(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        self.final_action = "changes_requested"
        self.final_action_body = body
