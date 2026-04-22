"""Unit tests for JiraClient using httpx MockTransport."""

from __future__ import annotations

import json

import httpx
import pytest

from ai_driven.jira.client import JiraClient


def _json_response(data, status_code=200):
    return httpx.Response(status_code, json=data)


@pytest.fixture
def jira_client_with_transport():
    """Factory that creates a JiraClient with a custom MockTransport, bypassing __init__."""
    def _factory(handler):
        client = JiraClient.__new__(JiraClient)
        client._base_url = "https://test.atlassian.net"
        client._acceptance_criteria_field = "customfield_10028"
        client._http = httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            base_url="https://test.atlassian.net/rest/api/3",
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        return client
    return _factory


async def test_get_issue(jira_client_with_transport):
    """Mock Jira issue response with ADF body and verify parsing."""
    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response({
            "key": "PROJ-123",
            "fields": {
                "summary": "Implement user authentication",
                "description": {
                    "version": 1,
                    "type": "doc",
                    "content": [
                        {
                            "type": "paragraph",
                            "content": [
                                {"type": "text", "text": "Add OAuth2 login flow."}
                            ],
                        }
                    ],
                },
                "status": {"name": "In Progress"},
                "issuetype": {"name": "Story"},
                "priority": {"name": "High"},
                "assignee": {"displayName": "Jane Doe"},
                "reporter": {"displayName": "John Smith"},
                "labels": ["backend", "auth"],
                "components": [{"name": "api"}, {"name": "auth"}],
                "subtasks": [{"key": "PROJ-124"}, {"key": "PROJ-125"}],
                "comment": {
                    "total": 1,
                    "maxResults": 50,
                    "comments": [
                        {
                            "id": "10001",
                            "author": {"displayName": "Jane Doe"},
                            "body": {
                                "type": "doc",
                                "content": [
                                    {
                                        "type": "paragraph",
                                        "content": [{"type": "text", "text": "Working on this."}],
                                    }
                                ],
                            },
                        }
                    ]
                },
            },
        })

    client = jira_client_with_transport(handler)
    issue = await client.get_issue("PROJ-123")

    assert issue.key == "PROJ-123"
    assert issue.summary == "Implement user authentication"
    assert "OAuth2" in issue.description
    assert issue.status == "In Progress"
    assert issue.issue_type == "Story"
    assert issue.assignee == "Jane Doe"
    assert issue.labels == ["backend", "auth"]
    assert issue.components == ["api", "auth"]
    assert len(issue.comments) == 1
    assert issue.comments[0].author == "Jane Doe"


async def test_add_comment(jira_client_with_transport):
    """Mock comment creation and verify response."""
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        return _json_response({
            "id": "10002",
            "author": {"displayName": "AI Bot"},
            "body": body["body"],
        }, status_code=201)

    client = jira_client_with_transport(handler)
    comment = await client.add_comment("PROJ-123", "Analysis complete. Ready for review.")

    assert comment.id == "10002"
    assert comment.author == "AI Bot"
    assert comment.body == "Analysis complete. Ready for review."


async def test_transition_issue(jira_client_with_transport):
    """Mock transitions list then transition, verify no error."""
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1

        if request.method == "GET":
            return _json_response({
                "transitions": [
                    {"id": "11", "name": "Start Progress", "to": {"name": "In Progress"}},
                    {"id": "21", "name": "Done", "to": {"name": "Done"}},
                    {"id": "31", "name": "In Review", "to": {"name": "In Review"}},
                ]
            })
        else:
            body = json.loads(request.content)
            assert body["transition"]["id"] == "21"
            return _json_response({}, status_code=204)

    client = jira_client_with_transport(handler)
    await client.transition_issue("PROJ-123", "Done")

    assert call_count == 2


async def test_transition_not_found_raises(jira_client_with_transport):
    """Mock transitions without target and verify ValueError."""
    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response({
            "transitions": [
                {"id": "11", "name": "Start Progress", "to": {"name": "In Progress"}},
                {"id": "21", "name": "Done", "to": {"name": "Done"}},
            ]
        })

    client = jira_client_with_transport(handler)

    with pytest.raises(ValueError, match="Transition 'Deploy' not found"):
        await client.transition_issue("PROJ-123", "Deploy")
