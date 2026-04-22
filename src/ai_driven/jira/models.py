"""Jira data models."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class JiraIssue:
    key: str
    summary: str
    description: str
    status: str
    issue_type: str
    priority: str
    assignee: str | None = None
    reporter: str | None = None
    labels: list[str] = field(default_factory=list)
    components: list[str] = field(default_factory=list)
    acceptance_criteria: str = ""
    subtasks: list[str] = field(default_factory=list)
    comments: list[JiraComment] = field(default_factory=list)
    created: datetime | None = None
    updated: datetime | None = None
    url: str = ""


@dataclass
class JiraComment:
    id: str
    author: str
    body: str
    created: datetime | None = None


@dataclass
class JiraTransition:
    id: str
    name: str
    to_status: str


@dataclass
class JiraSearchResult:
    issues: list[JiraIssue] = field(default_factory=list)
    total: int = 0
