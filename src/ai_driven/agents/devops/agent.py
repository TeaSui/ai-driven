"""AI DevOps Assistant — triages incidents from DataDog alerts."""

from __future__ import annotations

import logging
from typing import Any

from ai_driven.agents.base import BaseAgent
from ai_driven.claude.prompts.devops_agent import DEVOPS_AGENT_SYSTEM_PROMPT

logger = logging.getLogger(__name__)


class DevOpsAgent(BaseAgent):
    """AI DevOps Assistant that triages incidents and suggests remediation."""

    agent_type = "devops"

    def __init__(
        self,
        jira_client: Any = None,
        datadog_client: Any = None,
        scm_client: Any = None,
        slack_notifier: Any = None,
        **kwargs: Any,
    ) -> None:
        self._jira = jira_client
        self._datadog = datadog_client
        self._scm = scm_client
        self._slack = slack_notifier
        super().__init__(**kwargs)

    def _register_tools(self) -> None:
        """Register DevOps-specific tools."""

        @self._registry.tool(
            name="datadog.query_logs",
            description="Search DataDog logs for error patterns, service logs, or specific trace IDs.",
            input_schema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "DataDog log query, e.g. 'service:my-app status:error'",
                    },
                    "from_ts": {
                        "type": "string",
                        "description": "Start time in ISO 8601 format, e.g. '2026-04-20T00:00:00Z'",
                    },
                    "to_ts": {
                        "type": "string",
                        "description": "End time in ISO 8601 format, e.g. '2026-04-20T01:00:00Z'",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max number of log entries to return (default 50)",
                        "default": 50,
                    },
                },
                "required": ["query", "from_ts", "to_ts"],
            },
        )
        async def query_datadog_logs(
            query: str, from_ts: str, to_ts: str, limit: int = 50
        ) -> dict[str, Any] | list[dict[str, Any]]:
            if not self._datadog:
                return {"error": "DataDog client not configured"}
            logs = await self._datadog.search_logs(query, from_ts, to_ts, limit)
            return {"logs": logs, "count": len(logs)}

        @self._registry.tool(
            name="datadog.query_metrics",
            description="Fetch metric timeseries from DataDog (error rates, latency, throughput).",
            input_schema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "DataDog metrics query, e.g. 'avg:system.cpu.user{service:my-app}'",
                    },
                    "from_ts": {
                        "type": "integer",
                        "description": "Start time as Unix epoch seconds",
                    },
                    "to_ts": {
                        "type": "integer",
                        "description": "End time as Unix epoch seconds",
                    },
                },
                "required": ["query", "from_ts", "to_ts"],
            },
        )
        async def query_datadog_metrics(
            query: str, from_ts: int, to_ts: int
        ) -> dict[str, Any]:
            if not self._datadog:
                return {"error": "DataDog client not configured"}
            return await self._datadog.query_metrics(query, from_ts, to_ts)

        @self._registry.tool(
            name="deploy.get_recent",
            description="List recent deployments for a repository to correlate with incidents.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {
                        "type": "string",
                        "description": "Repository full name, e.g. 'org/service'",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max number of deployments to return (default 10)",
                        "default": 10,
                    },
                },
                "required": ["repo_full_name"],
            },
        )
        async def get_recent_deployments(
            repo_full_name: str, limit: int = 10
        ) -> dict[str, Any] | list[dict[str, Any]]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            # Fetch recent merged PRs as a proxy for deployments
            prs = await self._scm.list_recent_merges(repo_full_name, limit)
            return {
                "deployments": prs,
                "count": len(prs),
            }

        @self._registry.tool(
            name="datadog.get_service_health",
            description="Check service health status from DataDog APM, including error rate and latency.",
            input_schema={
                "type": "object",
                "properties": {
                    "service_name": {
                        "type": "string",
                        "description": "Service name as registered in DataDog APM",
                    },
                },
                "required": ["service_name"],
            },
        )
        async def get_service_health(service_name: str) -> dict[str, Any]:
            if not self._datadog:
                return {"error": "DataDog client not configured"}
            summary = await self._datadog.get_service_summary(service_name)
            monitors = await self._datadog.get_active_monitors(service_name)
            return {
                "service": service_name,
                "summary": summary,
                "active_monitors": monitors,
            }

        @self._registry.tool(
            name="jira.create_incident",
            description="Create a Jira issue of type Bug for incident tracking.",
            input_schema={
                "type": "object",
                "properties": {
                    "project_key": {
                        "type": "string",
                        "description": "Jira project key, e.g. 'OPS'",
                    },
                    "title": {
                        "type": "string",
                        "description": "Incident title/summary",
                    },
                    "description": {
                        "type": "string",
                        "description": "Detailed incident description",
                    },
                    "priority": {
                        "type": "string",
                        "description": "Priority: Highest, High, Medium, Low, Lowest",
                        "default": "High",
                    },
                    "labels": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Labels to attach, e.g. ['incident', 'auto-triage']",
                        "default": [],
                    },
                },
                "required": ["project_key", "title", "description"],
            },
        )
        async def create_jira_incident(
            project_key: str,
            title: str,
            description: str,
            priority: str = "High",
            labels: list[str] | None = None,
        ) -> dict[str, Any]:
            if not self._jira:
                return {"error": "Jira client not configured"}
            issue = await self._jira.create_issue(
                project_key=project_key,
                summary=title,
                description=description,
                issue_type="Bug",
                priority=priority,
                labels=labels or ["incident", "auto-triage"],
            )
            return {
                "key": issue.key,
                "url": issue.url,
                "status": "created",
            }

        @self._registry.tool(
            name="slack.notify_oncall",
            description="Send a Slack notification to the on-call channel about an incident.",
            input_schema={
                "type": "object",
                "properties": {
                    "message": {
                        "type": "string",
                        "description": "Notification message text",
                    },
                    "channel": {
                        "type": "string",
                        "description": "Slack channel override (uses default if omitted)",
                    },
                },
                "required": ["message"],
            },
        )
        async def notify_oncall(
            message: str, channel: str | None = None
        ) -> dict[str, str]:
            if not self._slack:
                return {"error": "Slack notifier not configured"}
            await self._slack.notify_phase_change(
                task_id=self._task_id or "incident",
                phase="Incident Alert",
                detail=message,
            )
            return {"status": "sent"}

        @self._registry.tool(
            name="deploy.suggest_rollback",
            description="Identify the deployment to rollback to based on incident timeline.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {
                        "type": "string",
                        "description": "Repository full name",
                    },
                    "incident_start_ts": {
                        "type": "string",
                        "description": "When the incident started (ISO 8601)",
                    },
                },
                "required": ["repo_full_name", "incident_start_ts"],
            },
        )
        async def suggest_rollback(
            repo_full_name: str, incident_start_ts: str
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            # Get recent merges to find the last safe deployment
            merges = await self._scm.list_recent_merges(repo_full_name, 10)
            if not merges:
                return {
                    "suggestion": "No recent deployments found",
                    "rollback_target": None,
                }

            # Find the last deployment before the incident start
            safe_deployment = None
            suspect_deployments = []
            for deploy in merges:
                merged_at = deploy.get("merged_at", "")
                if merged_at and merged_at < incident_start_ts:
                    if safe_deployment is None:
                        safe_deployment = deploy
                else:
                    suspect_deployments.append(deploy)

            return {
                "rollback_target": safe_deployment,
                "suspect_deployments": suspect_deployments,
                "suggestion": (
                    f"Rollback to deployment: {safe_deployment.get('title', 'unknown')}"
                    if safe_deployment
                    else "No safe deployment found before incident start"
                ),
            }

    async def _register_mcp_tools(self) -> None:
        """Register MCP tools if MCP is enabled."""
        from ai_driven.config import get_settings
        settings = get_settings()
        if not settings.mcp_enabled:
            return
        try:
            from ai_driven.mcp.tool_bridge import register_mcp_tools
            count = await register_mcp_tools(self._registry)
            if count:
                logger.info("DevOpsAgent: registered %d MCP tools", count)
        except Exception as e:
            logger.warning("MCP tool registration failed: %s", e)

    def _build_mcp_tools_section(self) -> str:
        """Build an XML section listing available MCP integration tools."""
        mcp_tools = [n for n in self._registry.tool_names if n.startswith("mcp_")]
        if not mcp_tools:
            return ""
        lines = ["<available_integrations>"]
        for name in mcp_tools:
            tool_def = self._registry.get_tool(name)
            desc = tool_def.schema.get("description", "") if tool_def else ""
            lines.append(f"- {name}: {desc}")
        lines.append("</available_integrations>")
        return "\n".join(lines)

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        prompt = DEVOPS_AGENT_SYSTEM_PROMPT
        mcp_section = self._build_mcp_tools_section()
        if mcp_section:
            prompt += f"\n\n{mcp_section}"
        return prompt

    def _get_tool_names(self) -> list[str]:
        return self._registry.tool_names

    async def run(
        self,
        task_input: dict[str, Any],
        *,
        task_id: str | None = None,
        model: str | None = None,
        max_iterations: int | None = None,
        use_thinking: bool = False,
        stop_sequences: list[str] | None = None,
    ) -> Any:
        """Register MCP tools before running."""
        await self._register_mcp_tools()
        return await super().run(
            task_input,
            task_id=task_id,
            model=model,
            max_iterations=max_iterations,
            use_thinking=use_thinking,
            stop_sequences=stop_sequences,
        )

    def _build_initial_message(self, task_input: dict[str, Any]) -> str:
        """Structure follows prompt-engineering.md: direct-action instruction
        line, then XML-tagged context blocks, then an XML-tagged <workflow>
        block so the triage steps read as structured guidance instead of
        free-form prose.
        """
        alert_title = task_input.get("title", "Unknown alert")
        alert_type = task_input.get("alert_type", "unknown")
        service = task_input.get("service", "")
        repo = task_input.get("repo_full_name", "")

        parts: list[str] = [
            "Triage the DataDog alert below and produce an incident report.",
            "",
            "<alert>",
            f"type: {alert_type}",
            f"title: {alert_title}",
        ]
        if service:
            parts.append(f"service: {service}")
        if repo:
            parts.append(f"repository: {repo}")
        parts.append("</alert>")
        parts.append("")

        parts.append("<full_payload>")
        parts.append(str(task_input))
        parts.append("</full_payload>")
        parts.append("")

        parts.append("<workflow>")
        parts.append("1. ASSESS: determine severity (P1-P4) from alert type and impact.")
        parts.append("2. GATHER: call datadog.query_logs and datadog.query_metrics "
                     "to scope the incident.")
        parts.append("3. CORRELATE: call deploy.get_recent to find recent changes.")
        parts.append("4. DIAGNOSE: call datadog.get_service_health to inspect service "
                     "status and active monitors.")
        parts.append("5. ACT: if rollback is needed, call deploy.suggest_rollback "
                     "to identify the target; then call jira.create_incident "
                     "and slack.notify_oncall to record and escalate.")
        parts.append("</workflow>")

        return "\n".join(parts)
