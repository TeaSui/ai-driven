"""Jira Cloud REST API client."""

from __future__ import annotations

import logging
from typing import Any

import httpx
from tenacity import (
    retry,
    retry_if_exception,
    stop_after_attempt,
    wait_exponential,
)

from ai_driven.config import get_settings
from ai_driven.jira.models import JiraComment, JiraIssue, JiraSearchResult, JiraTransition

logger = logging.getLogger(__name__)


def _is_retryable(exc: BaseException) -> bool:
    """Return True for HTTP 429 and 5xx errors."""
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code == 429 or exc.response.status_code >= 500
    return False


_retry_policy = retry(
    retry=retry_if_exception(_is_retryable),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)


class JiraClient:
    """Jira Cloud API client using httpx."""

    def __init__(
        self,
        base_url: str | None = None,
        email: str | None = None,
        api_token: str | None = None,
    ) -> None:
        settings = get_settings()
        self._base_url = (base_url or settings.jira_base_url).rstrip("/")
        self._acceptance_criteria_field = settings.jira_acceptance_criteria_field
        self._http = httpx.AsyncClient(
            base_url=f"{self._base_url}/rest/api/3",
            auth=(email or settings.jira_email, api_token or settings.jira_api_token),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            timeout=30.0,
        )

    async def close(self) -> None:
        await self._http.aclose()

    @_retry_policy
    async def get_issue(self, issue_key: str) -> JiraIssue:
        resp = await self._http.get(
            f"/issue/{issue_key}",
            params={"expand": "renderedFields"},
        )
        resp.raise_for_status()
        data = resp.json()
        fields = data["fields"]

        comments = await self._get_all_comments(issue_key, fields)

        description = self._extract_text(fields.get("description", {}))
        acceptance_criteria = ""
        ac_field = fields.get(self._acceptance_criteria_field)
        if ac_field:
            acceptance_criteria = self._extract_text(ac_field)

        return JiraIssue(
            key=data["key"],
            summary=fields.get("summary", ""),
            description=description,
            status=fields.get("status", {}).get("name", ""),
            issue_type=fields.get("issuetype", {}).get("name", ""),
            priority=fields.get("priority", {}).get("name", ""),
            assignee=fields.get("assignee", {}).get("displayName") if fields.get("assignee") else None,
            reporter=fields.get("reporter", {}).get("displayName") if fields.get("reporter") else None,
            labels=fields.get("labels", []),
            components=[c["name"] for c in fields.get("components", [])],
            acceptance_criteria=acceptance_criteria,
            subtasks=[st["key"] for st in fields.get("subtasks", [])],
            comments=comments,
            url=f"{self._base_url}/browse/{data['key']}",
        )

    async def _get_all_comments(
        self, issue_key: str, fields: dict[str, Any]
    ) -> list[JiraComment]:
        """Fetch all comments with pagination support."""
        comment_data = fields.get("comment", {})
        total = comment_data.get("total", 0)
        max_results = comment_data.get("maxResults", 0)
        comments: list[JiraComment] = []

        # Parse first page from the issue response
        for c in comment_data.get("comments", []):
            comments.append(self._parse_comment(c))

        # Fetch remaining pages if there are more comments
        start_at = max_results
        while start_at < total:
            resp = await self._http.get(
                f"/issue/{issue_key}/comment",
                params={"startAt": start_at, "maxResults": 50},
            )
            resp.raise_for_status()
            page = resp.json()
            for c in page.get("comments", []):
                comments.append(self._parse_comment(c))
            start_at += page.get("maxResults", 50)

        return comments

    @_retry_policy
    async def get_transitions(self, issue_key: str) -> list[JiraTransition]:
        resp = await self._http.get(f"/issue/{issue_key}/transitions")
        resp.raise_for_status()
        return [
            JiraTransition(
                id=t["id"],
                name=t["name"],
                to_status=t["to"]["name"],
            )
            for t in resp.json().get("transitions", [])
        ]

    @_retry_policy
    async def transition_issue(self, issue_key: str, transition_name: str) -> None:
        transitions = await self.get_transitions(issue_key)
        transition = next((t for t in transitions if t.name.lower() == transition_name.lower()), None)
        if not transition:
            available = [t.name for t in transitions]
            raise ValueError(f"Transition '{transition_name}' not found. Available: {available}")

        resp = await self._http.post(
            f"/issue/{issue_key}/transitions",
            json={"transition": {"id": transition.id}},
        )
        resp.raise_for_status()

    @_retry_policy
    async def add_comment(self, issue_key: str, body: str) -> JiraComment:
        adf_body = {
            "version": 1,
            "type": "doc",
            "content": [
                {"type": "paragraph", "content": [{"type": "text", "text": body}]}
            ],
        }
        resp = await self._http.post(
            f"/issue/{issue_key}/comment",
            json={"body": adf_body},
        )
        resp.raise_for_status()
        data = resp.json()
        return JiraComment(
            id=data["id"],
            author=data["author"].get("displayName", ""),
            body=body,
        )

    @_retry_policy
    async def search_issues(self, jql: str, max_results: int = 50) -> JiraSearchResult:
        """Search issues using JQL via /rest/api/3/search."""
        resp = await self._http.get(
            "/search",
            params={"jql": jql, "maxResults": max_results},
        )
        resp.raise_for_status()
        data = resp.json()

        issues: list[JiraIssue] = []
        for item in data.get("issues", []):
            fields = item["fields"]
            issues.append(JiraIssue(
                key=item["key"],
                summary=fields.get("summary", ""),
                description=self._extract_text(fields.get("description", {})),
                status=fields.get("status", {}).get("name", ""),
                issue_type=fields.get("issuetype", {}).get("name", ""),
                priority=fields.get("priority", {}).get("name", ""),
                assignee=fields.get("assignee", {}).get("displayName") if fields.get("assignee") else None,
                reporter=fields.get("reporter", {}).get("displayName") if fields.get("reporter") else None,
                labels=fields.get("labels", []),
                components=[c["name"] for c in fields.get("components", [])],
                url=f"{self._base_url}/browse/{item['key']}",
            ))

        return JiraSearchResult(issues=issues, total=data.get("total", 0))

    @_retry_policy
    async def get_issue_links(self, issue_key: str) -> list[dict[str, str]]:
        """Get linked issues for a given issue key."""
        resp = await self._http.get(
            f"/issue/{issue_key}",
            params={"fields": "issuelinks"},
        )
        resp.raise_for_status()
        data = resp.json()

        links: list[dict[str, str]] = []
        for link in data.get("fields", {}).get("issuelinks", []):
            link_type = link.get("type", {}).get("name", "")
            if "outwardIssue" in link:
                linked = link["outwardIssue"]
                direction = link.get("type", {}).get("outward", "")
                links.append({
                    "key": linked["key"],
                    "type": link_type,
                    "direction": direction,
                    "summary": linked.get("fields", {}).get("summary", ""),
                    "status": linked.get("fields", {}).get("status", {}).get("name", ""),
                })
            if "inwardIssue" in link:
                linked = link["inwardIssue"]
                direction = link.get("type", {}).get("inward", "")
                links.append({
                    "key": linked["key"],
                    "type": link_type,
                    "direction": direction,
                    "summary": linked.get("fields", {}).get("summary", ""),
                    "status": linked.get("fields", {}).get("status", {}).get("name", ""),
                })

        return links

    @_retry_policy
    async def assign_issue(self, issue_key: str, account_id: str) -> None:
        """Assign an issue to a user by account ID."""
        resp = await self._http.put(
            f"/issue/{issue_key}/assignee",
            json={"accountId": account_id},
        )
        resp.raise_for_status()

    @staticmethod
    def _parse_comment(c: dict[str, Any]) -> JiraComment:
        return JiraComment(
            id=c["id"],
            author=c["author"].get("displayName", ""),
            body=JiraClient._extract_text(c.get("body", {})),
        )

    @staticmethod
    def _extract_text(adf: dict[str, Any] | str | None) -> str:
        """Extract plain text from Atlassian Document Format."""
        if adf is None:
            return ""
        if isinstance(adf, str):
            return adf

        texts: list[str] = []

        def walk(node: dict[str, Any] | list[Any], list_counter: int | None = None) -> None:
            if isinstance(node, list):
                for item in node:
                    walk(item, list_counter)
                return
            if not isinstance(node, dict):
                return

            node_type = node.get("type", "")
            content = node.get("content", [])

            if node_type == "text":
                texts.append(node.get("text", ""))
                return

            if node_type == "mention":
                texts.append(node.get("attrs", {}).get("text", ""))
                return

            if node_type in ("inlineCard", "blockCard"):
                url = node.get("attrs", {}).get("url", "")
                if url:
                    texts.append(url)
                return

            if node_type == "heading":
                texts.append("\n")
                for child in content:
                    walk(child)
                texts.append("\n")
                return

            if node_type == "bulletList":
                for item in content:
                    if item.get("type") == "listItem":
                        texts.append("\n- ")
                        for child in item.get("content", []):
                            walk(child)
                return

            if node_type == "orderedList":
                counter = 0
                for item in content:
                    if item.get("type") == "listItem":
                        counter += 1
                        texts.append(f"\n{counter}. ")
                        for child in item.get("content", []):
                            walk(child)
                return

            if node_type == "codeBlock":
                texts.append("\n```\n")
                for child in content:
                    walk(child)
                texts.append("\n```\n")
                return

            if node_type == "table":
                for row in content:
                    if row.get("type") == "tableRow":
                        row_texts: list[str] = []
                        for cell in row.get("content", []):
                            cell_parts: list[str] = []
                            for child in cell.get("content", []):
                                saved = len(texts)
                                walk(child)
                                cell_parts.extend(texts[saved:])
                                del texts[saved:]
                            row_texts.append("".join(cell_parts).strip())
                        texts.append(" | ".join(row_texts))
                        texts.append("\n")
                return

            # Default: recurse into children (paragraph, doc, etc.)
            for child in content:
                walk(child)

        walk(adf)
        return "".join(texts).strip()
