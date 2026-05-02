"""MCP server configurations."""

from __future__ import annotations
from dataclasses import dataclass, field
from ai_driven.config import get_settings


@dataclass
class McpServerConfig:
    """Configuration for an MCP server connection."""
    name: str
    command: list[str]  # For stdio transport
    env: dict[str, str] = field(default_factory=dict)
    enabled: bool = True


def get_mcp_servers() -> list[McpServerConfig]:
    """Return configured MCP server definitions."""
    settings = get_settings()
    servers: list[McpServerConfig] = []

    if settings.mcp_github_enabled and settings.github_token:
        servers.append(McpServerConfig(
            name="github",
            command=["npx", "-y", "@modelcontextprotocol/server-github"],
            env={"GITHUB_PERSONAL_ACCESS_TOKEN": settings.github_token},
        ))

    if settings.mcp_context7_enabled:
        servers.append(McpServerConfig(
            name="context7",
            command=["npx", "-y", "@upstash/context7-mcp"],
        ))

    if settings.mcp_jira_enabled and settings.jira_base_url and settings.jira_api_token:
        servers.append(McpServerConfig(
            name="jira",
            command=["npx", "-y", "@anthropic/mcp-server-atlassian"],
            env={
                "JIRA_BASE_URL": settings.jira_base_url,
                "JIRA_EMAIL": settings.jira_email,
                "JIRA_API_TOKEN": settings.jira_api_token,
            },
        ))

    if settings.mcp_slack_enabled and settings.slack_bot_token:
        servers.append(McpServerConfig(
            name="slack",
            command=["npx", "-y", "@anthropic/mcp-server-slack"],
            env={"SLACK_BOT_TOKEN": settings.slack_bot_token},
        ))

    if settings.mcp_datadog_enabled and settings.dd_api_key and settings.dd_app_key:
        servers.append(McpServerConfig(
            name="datadog",
            command=["npx", "-y", "@anthropic/mcp-server-datadog"],
            env={
                "DD_API_KEY": settings.dd_api_key,
                "DD_APP_KEY": settings.dd_app_key,
            },
        ))

    return servers
