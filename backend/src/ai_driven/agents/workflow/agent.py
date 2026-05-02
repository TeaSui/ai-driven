"""AI Workflow Platform — orchestrates the full SDLC pipeline."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from ai_driven.agents.base import BaseAgent
from ai_driven.claude.prompts.workflow_agent import WORKFLOW_AGENT_SYSTEM_PROMPT
from ai_driven.queue.producer import TaskProducer
from ai_driven.slack_bot.notifications import SlackNotifier

logger = logging.getLogger(__name__)


class WorkflowAgent(BaseAgent):
    """AI Workflow orchestrator that coordinates the SDLC pipeline."""

    agent_type = "workflow"

    def __init__(
        self,
        scm_client: Any = None,
        task_producer: TaskProducer | None = None,
        slack_notifier: SlackNotifier | None = None,
        **kwargs: Any,
    ) -> None:
        self._scm = scm_client
        self._producer = task_producer
        self._notifier = slack_notifier
        super().__init__(**kwargs)

    def _register_tools(self) -> None:
        @self._registry.tool(
            name="workflow.dispatch_coding",
            description=(
                "Create a coding task for a Jira ticket and enqueue it to the coding agent queue. "
                "Returns the new task ID."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "jira_key": {
                        "type": "string",
                        "description": "Jira issue key, e.g. 'PROJ-123'",
                    },
                    "repo_full_name": {
                        "type": "string",
                        "description": "Repository full name, e.g. 'org/repo'",
                    },
                    "title": {
                        "type": "string",
                        "description": "Human-readable task title",
                    },
                    "instructions": {
                        "type": "string",
                        "description": "Additional instructions for the coding agent",
                        "default": "",
                    },
                },
                "required": ["jira_key", "repo_full_name", "title"],
            },
        )
        async def dispatch_coding_agent(
            jira_key: str,
            repo_full_name: str,
            title: str,
            instructions: str = "",
        ) -> dict[str, Any]:
            if not self._producer:
                return {"error": "Task producer not configured"}

            task_id = str(uuid.uuid4())
            payload = {
                "jira_key": jira_key,
                "repo_full_name": repo_full_name,
                "title": title,
                "instructions": instructions,
            }

            message_id = await self._producer.enqueue_task(
                task_id=task_id,
                agent_type="coding",
                payload=payload,
            )

            logger.info(
                "Dispatched coding task %s for %s (msg_id=%s)",
                task_id,
                jira_key,
                message_id,
            )
            return {
                "task_id": task_id,
                "agent_type": "coding",
                "jira_key": jira_key,
                "status": "queued",
                "message_id": message_id,
            }

        @self._registry.tool(
            name="workflow.dispatch_review",
            description=(
                "Create a review task for a pull request and enqueue it to the review agent queue."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {
                        "type": "string",
                        "description": "Repository full name, e.g. 'org/repo'",
                    },
                    "pr_number": {
                        "type": "integer",
                        "description": "Pull request number",
                    },
                    "pr_title": {
                        "type": "string",
                        "description": "Pull request title",
                    },
                    "pr_description": {
                        "type": "string",
                        "description": "Pull request description",
                        "default": "",
                    },
                },
                "required": ["repo_full_name", "pr_number", "pr_title"],
            },
        )
        async def dispatch_review_agent(
            repo_full_name: str,
            pr_number: int,
            pr_title: str,
            pr_description: str = "",
        ) -> dict[str, Any]:
            if not self._producer:
                return {"error": "Task producer not configured"}

            task_id = str(uuid.uuid4())
            payload = {
                "repo_full_name": repo_full_name,
                "pr_number": pr_number,
                "pr_title": pr_title,
                "pr_description": pr_description,
            }

            message_id = await self._producer.enqueue_task(
                task_id=task_id,
                agent_type="review",
                payload=payload,
            )

            logger.info(
                "Dispatched review task %s for PR #%d (msg_id=%s)",
                task_id,
                pr_number,
                message_id,
            )
            return {
                "task_id": task_id,
                "agent_type": "review",
                "pr_number": pr_number,
                "status": "queued",
                "message_id": message_id,
            }

        @self._registry.tool(
            name="workflow.check_task_status",
            description="Check the current status of a dispatched task by its ID.",
            input_schema={
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "UUID of the task to check",
                    },
                },
                "required": ["task_id"],
            },
        )
        async def check_task_status(task_id: str) -> dict[str, Any]:
            from ai_driven.db.dynamo.models import TaskStateMachineStore

            store = TaskStateMachineStore()
            transitions = store.get_transitions(task_id)
            if not transitions:
                return {"task_id": task_id, "status": "unknown", "transitions": []}

            latest = transitions[-1]
            return {
                "task_id": task_id,
                "status": latest.get("to_state", "unknown"),
                "transition_count": len(transitions),
                "last_transition": {
                    "from": latest.get("from_state"),
                    "to": latest.get("to_state"),
                    "timestamp": latest.get("state_timestamp"),
                },
            }

        @self._registry.tool(
            name="github.merge_pr",
            description="Merge a pull request via the SCM client.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {
                        "type": "string",
                        "description": "Repository full name, e.g. 'org/repo'",
                    },
                    "pr_number": {
                        "type": "integer",
                        "description": "Pull request number to merge",
                    },
                    "merge_method": {
                        "type": "string",
                        "description": "Merge strategy: squash, merge, or rebase",
                        "default": "squash",
                        "enum": ["squash", "merge", "rebase"],
                    },
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def merge_pull_request(
            repo_full_name: str,
            pr_number: int,
            merge_method: str = "squash",
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}

            try:
                await self._scm.merge_pull_request(
                    repo_full_name, pr_number, merge_method
                )
                logger.info(
                    "Merged PR #%d in %s (%s)", pr_number, repo_full_name, merge_method
                )
                return {
                    "status": "merged",
                    "pr_number": pr_number,
                    "repo_full_name": repo_full_name,
                    "merge_method": merge_method,
                }
            except Exception as exc:
                logger.error(
                    "Failed to merge PR #%d in %s: %s",
                    pr_number,
                    repo_full_name,
                    exc,
                )
                return {"error": f"Merge failed: {exc}"}

        @self._registry.tool(
            name="slack.notify_team",
            description="Send a Slack notification to the team channel.",
            input_schema={
                "type": "object",
                "properties": {
                    "message": {
                        "type": "string",
                        "description": "Notification message (supports Slack mrkdwn)",
                    },
                    "channel": {
                        "type": "string",
                        "description": "Slack channel override (optional, uses default if omitted)",
                        "default": "",
                    },
                },
                "required": ["message"],
            },
        )
        async def notify_team(message: str, channel: str = "") -> dict[str, str]:
            if not self._notifier:
                return {"error": "Slack notifier not configured"}

            try:
                await self._notifier._send(
                    text=message,
                    channel=channel or None,
                )
                return {"status": "sent"}
            except Exception as exc:
                logger.error("Failed to send Slack notification: %s", exc)
                return {"error": f"Notification failed: {exc}"}

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
                logger.info("WorkflowAgent: registered %d MCP tools", count)
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
        prompt = WORKFLOW_AGENT_SYSTEM_PROMPT
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
        return str(task_input.get("message", task_input))
