"""Shared SCM data models."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class SCMType(str, Enum):
    GITHUB = "github"
    BITBUCKET = "bitbucket"


class PRState(str, Enum):
    OPEN = "open"
    CLOSED = "closed"
    MERGED = "merged"


@dataclass
class Repository:
    full_name: str
    scm_type: SCMType
    default_branch: str
    clone_url: str
    description: str = ""


@dataclass
class FileContent:
    path: str
    content: str
    encoding: str = "utf-8"
    size: int = 0


@dataclass
class DirectoryEntry:
    path: str
    name: str
    is_directory: bool
    size: int = 0


@dataclass
class PullRequest:
    number: int
    title: str
    body: str
    state: PRState
    source_branch: str
    target_branch: str
    author: str
    url: str
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass
class PRComment:
    id: str
    body: str
    author: str
    file_path: str | None = None
    line: int | None = None
    created_at: datetime | None = None


@dataclass
class Commit:
    sha: str
    message: str
    author: str
    timestamp: datetime


@dataclass
class DiffFile:
    path: str
    status: str  # added, modified, deleted, renamed
    additions: int = 0
    deletions: int = 0
    patch: str = ""


@dataclass
class PRDiff:
    files: list[DiffFile] = field(default_factory=list)
    total_additions: int = 0
    total_deletions: int = 0
