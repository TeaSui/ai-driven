"""Model-based grading of agent task outputs using Claude Haiku."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

from ai_driven.claude.provider import create_provider
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

CODING_DIMENSIONS = {
    "correctness": "Does the output correctly address the requirements? Are there bugs or logic errors?",
    "completeness": "Does the output cover all requirements? Are edge cases handled?",
    "code_quality": "Is the code well-structured, readable, and following best practices?",
    "security": "Are there security vulnerabilities? Are inputs validated properly?",
}

REVIEW_DIMENSIONS = {
    "issue_detection": "Does the review correctly identify real bugs, logic errors, and code smells?",
    "false_positive_rate": "Are the flagged issues legitimate? Are there false alarms or nitpicks masquerading as real issues?",
    "actionability": "Are review comments specific, actionable, and include concrete suggestions for fixes?",
    "tone": "Is the review constructive, professional, and balanced between praise and criticism?",
}

DEVOPS_DIMENSIONS = {
    "root_cause_accuracy": "Is the root cause analysis correct and evidence-based?",
    "completeness": "Does the analysis cover all relevant signals (metrics, logs, deployments)?",
    "actionability": "Are the recommended actions specific, prioritized, and feasible?",
    "urgency_assessment": "Is the severity/priority assessment appropriate for the situation?",
}

# Default coding dimensions for backward compatibility
GRADING_DIMENSIONS = CODING_DIMENSIONS

_AGENT_DIMENSIONS: dict[str, dict[str, str]] = {
    "coding": CODING_DIMENSIONS,
    "review": REVIEW_DIMENSIONS,
    "devops": DEVOPS_DIMENSIONS,
}

_AGENT_WEIGHTS: dict[str, dict[str, float]] = {
    "coding": {"correctness": 0.35, "completeness": 0.25, "code_quality": 0.25, "security": 0.15},
    "review": {"issue_detection": 0.35, "false_positive_rate": 0.25, "actionability": 0.25, "tone": 0.15},
    "devops": {"root_cause_accuracy": 0.35, "completeness": 0.25, "actionability": 0.25, "urgency_assessment": 0.15},
}

GRADER_SYSTEM_PROMPT = """\
You are an expert code evaluator. Grade the AI agent's output on a specific dimension.

<instructions>
1. Analyze the task input (what was asked) and the agent output (what was produced).
2. Grade on the specified dimension using a 1-10 scale.
3. Provide specific reasoning, strengths, and weaknesses.
4. Be rigorous — avoid defaulting to mid-range scores. Use the full scale.
</instructions>

<scale>
1-2: Completely wrong or missing
3-4: Major issues, partially addresses the task
5-6: Adequate but with notable gaps
7-8: Good with minor issues
9-10: Excellent, comprehensive, production-ready
</scale>

<output_format>
Respond with ONLY a JSON object:
```json
{
  "score": <integer 1-10>,
  "reasoning": "<2-3 sentences explaining the score>",
  "strengths": ["<specific strength 1>", "<specific strength 2>"],
  "weaknesses": ["<specific weakness 1>", "<specific weakness 2>"]
}
```
</output_format>
"""


@dataclass
class EvalResult:
    dimension: str
    score: int
    reasoning: str
    strengths: list[str]
    weaknesses: list[str]
    grader_model: str


class TaskGrader:
    """Grades agent outputs using Claude Haiku as a model-based grader."""

    def __init__(self) -> None:
        self._provider = create_provider(resilient=False)
        self._settings = get_settings()

    async def grade_dimension(
        self,
        dimension: str,
        task_input: str,
        agent_output: str,
        agent_type: str = "coding",
    ) -> EvalResult:
        """Grade an agent output on a single dimension."""
        dims = _AGENT_DIMENSIONS.get(agent_type, CODING_DIMENSIONS)
        description = dims.get(dimension, dimension)
        model = self._settings.claude_fast_model

        user_message = (
            f"<dimension>{dimension}: {description}</dimension>\n\n"
            f"<task_input>\n{task_input[:3000]}\n</task_input>\n\n"
            f"<agent_output>\n{agent_output[:5000]}\n</agent_output>"
        )

        response = await self._provider.create_message(
            model=model,
            max_tokens=1024,
            messages=[{"role": "user", "content": user_message}],
            system=GRADER_SYSTEM_PROMPT,
            temperature=0.0,
        )

        return self._parse_grade(response.text, dimension, model)

    async def grade_all(
        self,
        task_input: str,
        agent_output: str,
        dimensions: list[str] | None = None,
        agent_type: str = "coding",
    ) -> list[EvalResult]:
        """Grade an agent output on all dimensions."""
        dim_set = _AGENT_DIMENSIONS.get(agent_type, CODING_DIMENSIONS)
        dims = dimensions or list(dim_set.keys())
        results: list[EvalResult] = []

        for dim in dims:
            try:
                result = await self.grade_dimension(dim, task_input, agent_output, agent_type)
                results.append(result)
            except Exception:
                logger.warning("Failed to grade dimension: %s", dim)

        return results

    @staticmethod
    def _parse_grade(text: str, dimension: str, model: str) -> EvalResult:
        """Parse the grader's JSON response."""
        # Extract JSON from response (handle markdown code blocks)
        json_str = text.strip()
        if "```json" in json_str:
            json_str = json_str.split("```json")[1].split("```")[0].strip()
        elif "```" in json_str:
            json_str = json_str.split("```")[1].split("```")[0].strip()

        try:
            data = json.loads(json_str)
        except json.JSONDecodeError:
            return EvalResult(
                dimension=dimension,
                score=5,
                reasoning=f"Failed to parse grader response: {text[:200]}",
                strengths=[],
                weaknesses=["Grader response was not valid JSON"],
                grader_model=model,
            )

        return EvalResult(
            dimension=dimension,
            score=max(1, min(10, int(data.get("score", 5)))),
            reasoning=str(data.get("reasoning", "")),
            strengths=data.get("strengths", []),
            weaknesses=data.get("weaknesses", []),
            grader_model=model,
        )

    @staticmethod
    def combined_score(results: list[EvalResult], agent_type: str = "coding") -> float:
        """Calculate weighted average score across dimensions."""
        if not results:
            return 0.0
        weights = _AGENT_WEIGHTS.get(agent_type, _AGENT_WEIGHTS["coding"])
        total_weight = 0.0
        weighted_sum = 0.0
        for r in results:
            w = weights.get(r.dimension, 0.25)
            weighted_sum += r.score * w
            total_weight += w
        return round(weighted_sum / total_weight, 2) if total_weight else 0.0
