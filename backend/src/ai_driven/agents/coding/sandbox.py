"""Sandbox execution -- runs Claude Code CLI in an isolated Docker container.

Docker is required; there is no mock mode. ``AI_DRIVEN_SANDBOX_MODE`` is
kept for forward-compatibility with future backends (e.g. Firecracker) but
currently only ``docker`` is accepted.
"""

from __future__ import annotations

import asyncio
import logging
import shutil
import tempfile
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

SANDBOX_IMAGE = "ai-driven-sandbox:latest"
MAX_TIMEOUT = 900  # 15 minutes max


@dataclass
class SandboxResult:
    success: bool
    stdout: str = ""
    stderr: str = ""
    exit_code: int = 0
    diff: str = ""
    changed_files: list[str] = field(default_factory=list)


class BaseSandboxExecutor(ABC):
    """Abstract sandbox interface."""

    @abstractmethod
    async def execute(
        self,
        repo_url: str,
        branch: str,
        task_prompt: str,
        *,
        clone_token: str | None = None,
        timeout: int = 600,
    ) -> SandboxResult:
        ...

    @abstractmethod
    async def run_command(
        self,
        command: str,
        working_dir: str = ".",
        timeout_seconds: int = 120,
    ) -> SandboxResult:
        """Run a single shell command in the sandbox."""
        ...


class SandboxExecutor(BaseSandboxExecutor):
    """Execute code generation tasks in an isolated Docker sandbox."""

    def __init__(self, image: str = SANDBOX_IMAGE) -> None:
        self._image = image

    async def run_command(
        self,
        command: str,
        working_dir: str = ".",
        timeout_seconds: int = 120,
    ) -> SandboxResult:
        """Run a single command inside the Docker sandbox."""
        settings = get_settings()
        timeout_seconds = min(timeout_seconds, MAX_TIMEOUT)

        cmd = [
            "docker", "run", "--rm",
            "--memory=4g", "--cpus=2", "--network=none",
            "-w", "/workspace",
            self._image,
            "bash", "-c", command,
        ]

        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(
                proc.communicate(), timeout=timeout_seconds
            )
            return SandboxResult(
                success=proc.returncode == 0,
                stdout=stdout.decode("utf-8", errors="replace"),
                stderr=stderr.decode("utf-8", errors="replace"),
                exit_code=proc.returncode or 0,
            )
        except asyncio.TimeoutError:
            return SandboxResult(
                success=False,
                stderr=f"Command timed out after {timeout_seconds}s",
                exit_code=124,
            )

    async def execute(
        self,
        repo_url: str,
        branch: str,
        task_prompt: str,
        *,
        clone_token: str | None = None,
        timeout: int = 600,
    ) -> SandboxResult:
        """Clone repo, run Claude Code CLI in sandbox, push changes."""
        timeout = min(timeout, MAX_TIMEOUT)
        work_dir = tempfile.mkdtemp(prefix="ai-driven-sandbox-")

        try:
            # Inject token into clone URL if provided
            clone_url = repo_url
            if clone_token and "github.com" in repo_url:
                clone_url = repo_url.replace(
                    "https://github.com",
                    f"https://x-access-token:{clone_token}@github.com",
                )
            elif clone_token and "bitbucket.org" in repo_url:
                clone_url = repo_url.replace(
                    "https://bitbucket.org",
                    f"https://x-token-auth:{clone_token}@bitbucket.org",
                )

            # Clone repository
            await self._run_cmd(
                f"git clone --depth=1 {clone_url} {work_dir}", timeout=60
            )

            # Create and checkout feature branch
            await self._run_cmd(
                f"git -C {work_dir} checkout -b {branch}", timeout=10
            )

            # Run Claude Code CLI in Docker sandbox
            result = await self._run_in_docker(work_dir, task_prompt, timeout)

            if result.exit_code != 0:
                return SandboxResult(
                    success=False,
                    stdout=result.stdout,
                    stderr=result.stderr,
                    exit_code=result.exit_code,
                )

            # Collect changes
            diff_result = await self._run_cmd(
                f"git -C {work_dir} diff", timeout=30
            )
            files_result = await self._run_cmd(
                f"git -C {work_dir} diff --name-only", timeout=10
            )
            changed_files = [
                f for f in files_result.stdout.strip().split("\n") if f
            ]

            if not changed_files:
                return SandboxResult(
                    success=False,
                    stdout=result.stdout,
                    stderr="No files were changed",
                    exit_code=1,
                )

            # Commit and push
            await self._run_cmd(f"git -C {work_dir} add -A", timeout=10)
            await self._run_cmd(
                f'git -C {work_dir} commit -m "feat: AI-generated implementation"',
                timeout=10,
            )
            await self._run_cmd(
                f"git -C {work_dir} push origin {branch}", timeout=60
            )

            return SandboxResult(
                success=True,
                stdout=result.stdout,
                stderr=result.stderr,
                exit_code=0,
                diff=diff_result.stdout,
                changed_files=changed_files,
            )

        except asyncio.TimeoutError:
            logger.error("Sandbox execution timed out after %ds", timeout)
            return SandboxResult(
                success=False,
                stderr=f"Execution timed out after {timeout}s",
                exit_code=124,
            )
        except Exception as e:
            logger.error("Sandbox execution failed: %s", e)
            return SandboxResult(success=False, stderr=str(e), exit_code=1)
        finally:
            shutil.rmtree(work_dir, ignore_errors=True)

    async def _run_in_docker(
        self, work_dir: str, prompt: str, timeout: int
    ) -> SandboxResult:
        """Run Claude Code CLI inside a Docker container."""
        settings = get_settings()

        cmd = [
            "docker",
            "run",
            "--rm",
            "--memory=4g",
            "--cpus=2",
            "--network=none",
            "-v",
            f"{work_dir}:/workspace",
            "-w",
            "/workspace",
            "-e",
            f"ANTHROPIC_API_KEY={settings.anthropic_api_key}",
            self._image,
            "claude",
            "-p",
            prompt,
            "--allowedTools",
            "Edit,Write,Bash",
        ]

        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(
                proc.communicate(), timeout=timeout
            )

            return SandboxResult(
                success=proc.returncode == 0,
                stdout=stdout.decode("utf-8", errors="replace"),
                stderr=stderr.decode("utf-8", errors="replace"),
                exit_code=proc.returncode or 0,
            )
        except asyncio.TimeoutError:
            proc.kill()
            raise

    @staticmethod
    async def _run_cmd(cmd: str, timeout: int = 60) -> SandboxResult:
        """Run a shell command and return result."""
        proc = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=timeout
        )

        return SandboxResult(
            success=proc.returncode == 0,
            stdout=stdout.decode("utf-8", errors="replace"),
            stderr=stderr.decode("utf-8", errors="replace"),
            exit_code=proc.returncode or 0,
        )


def create_sandbox() -> BaseSandboxExecutor:
    """Factory: return the Docker sandbox.

    The only supported mode is ``docker``. Any other value in
    ``AI_DRIVEN_SANDBOX_MODE`` raises — we do not silently substitute a
    mock, because that was how fictional pipeline data used to appear in
    real workflows.
    """
    settings = get_settings()
    if settings.sandbox_mode != "docker":
        raise RuntimeError(
            f"AI_DRIVEN_SANDBOX_MODE={settings.sandbox_mode!r} is not supported. "
            "Only 'docker' is valid. Mock sandbox has been removed."
        )
    logger.info("Using Docker sandbox executor")
    return SandboxExecutor()
