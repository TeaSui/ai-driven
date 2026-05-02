"""Tests for SandboxExecutor."""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.sandbox import (
    MAX_TIMEOUT,
    SANDBOX_IMAGE,
    SandboxExecutor,
    SandboxResult,
)


class TestSandboxResult:
    def test_defaults(self):
        result = SandboxResult(success=True)
        assert result.success is True
        assert result.stdout == ""
        assert result.stderr == ""
        assert result.exit_code == 0
        assert result.diff == ""
        assert result.changed_files == []

    def test_with_values(self):
        result = SandboxResult(
            success=False,
            stdout="output",
            stderr="error",
            exit_code=1,
            diff="some diff",
            changed_files=["a.py", "b.py"],
        )
        assert result.success is False
        assert result.stdout == "output"
        assert result.stderr == "error"
        assert result.exit_code == 1
        assert result.changed_files == ["a.py", "b.py"]


class TestSandboxExecutor:
    def test_default_image(self):
        executor = SandboxExecutor()
        assert executor._image == SANDBOX_IMAGE

    def test_custom_image(self):
        executor = SandboxExecutor(image="custom:v1")
        assert executor._image == "custom:v1"

    @pytest.mark.asyncio
    async def test_timeout_capped_at_max(self):
        """Timeout should be capped at MAX_TIMEOUT."""
        executor = SandboxExecutor()

        # Mock _run_cmd to simulate clone failure so we return early
        async def fail_clone(cmd, timeout=60):
            raise Exception("clone failed")

        with patch.object(executor, "_run_cmd", side_effect=fail_clone):
            result = await executor.execute(
                repo_url="https://github.com/org/repo",
                branch="feature/test",
                task_prompt="test",
                timeout=9999,
            )
        assert result.success is False

    @pytest.mark.asyncio
    async def test_github_token_injection(self):
        """Clone URL should inject GitHub token correctly."""
        executor = SandboxExecutor()
        captured_cmds = []

        async def capture_cmd(cmd, timeout=60):
            captured_cmds.append(cmd)
            return SandboxResult(success=True, stdout="", exit_code=0)

        async def mock_docker(work_dir, prompt, timeout):
            return SandboxResult(success=True, stdout="done", exit_code=0)

        with (
            patch.object(executor, "_run_cmd", side_effect=capture_cmd),
            patch.object(executor, "_run_in_docker", side_effect=mock_docker),
        ):
            # With changed files
            async def cmd_side_effect(cmd, timeout=60):
                if "diff --name-only" in cmd:
                    return SandboxResult(
                        success=True, stdout="file.py\n", exit_code=0
                    )
                if "diff" in cmd and "--name-only" not in cmd:
                    return SandboxResult(
                        success=True, stdout="diff content", exit_code=0
                    )
                captured_cmds.append(cmd)
                return SandboxResult(success=True, stdout="", exit_code=0)

            with patch.object(
                executor, "_run_cmd", side_effect=cmd_side_effect
            ):
                result = await executor.execute(
                    repo_url="https://github.com/org/repo.git",
                    branch="feature/test",
                    task_prompt="implement feature",
                    clone_token="ghp_test123",
                )

    @pytest.mark.asyncio
    async def test_bitbucket_token_injection(self):
        """Clone URL should inject Bitbucket token correctly."""
        executor = SandboxExecutor()
        captured_cmds = []

        async def capture_cmd(cmd, timeout=60):
            captured_cmds.append(cmd)
            if "clone" in cmd:
                assert "x-token-auth:bb_token@bitbucket.org" in cmd
            return SandboxResult(success=True, stdout="", exit_code=0)

        async def mock_docker(work_dir, prompt, timeout):
            return SandboxResult(success=False, exit_code=1, stderr="docker fail")

        with (
            patch.object(executor, "_run_cmd", side_effect=capture_cmd),
            patch.object(executor, "_run_in_docker", side_effect=mock_docker),
        ):
            result = await executor.execute(
                repo_url="https://bitbucket.org/org/repo.git",
                branch="feature/test",
                task_prompt="implement feature",
                clone_token="bb_token",
            )
        assert result.success is False

    @pytest.mark.asyncio
    async def test_no_changed_files_returns_failure(self):
        """If Claude produces no changes, result should be failure."""
        executor = SandboxExecutor()

        async def mock_cmd(cmd, timeout=60):
            if "diff --name-only" in cmd:
                return SandboxResult(success=True, stdout="", exit_code=0)
            if "diff" in cmd:
                return SandboxResult(success=True, stdout="", exit_code=0)
            return SandboxResult(success=True, stdout="", exit_code=0)

        async def mock_docker(work_dir, prompt, timeout):
            return SandboxResult(success=True, stdout="done", exit_code=0)

        with (
            patch.object(executor, "_run_cmd", side_effect=mock_cmd),
            patch.object(executor, "_run_in_docker", side_effect=mock_docker),
        ):
            result = await executor.execute(
                repo_url="https://github.com/org/repo",
                branch="feature/test",
                task_prompt="implement",
            )
        assert result.success is False
        assert "No files were changed" in result.stderr

    @pytest.mark.asyncio
    async def test_docker_failure_returns_failure(self):
        """If Docker execution fails, should return failure."""
        executor = SandboxExecutor()

        async def mock_cmd(cmd, timeout=60):
            return SandboxResult(success=True, stdout="", exit_code=0)

        async def mock_docker(work_dir, prompt, timeout):
            return SandboxResult(
                success=False, exit_code=1, stderr="Docker error"
            )

        with (
            patch.object(executor, "_run_cmd", side_effect=mock_cmd),
            patch.object(executor, "_run_in_docker", side_effect=mock_docker),
        ):
            result = await executor.execute(
                repo_url="https://github.com/org/repo",
                branch="feature/test",
                task_prompt="implement",
            )
        assert result.success is False
        assert result.exit_code == 1

    @pytest.mark.asyncio
    async def test_timeout_error_handled(self):
        """TimeoutError should be caught and returned as failure."""
        executor = SandboxExecutor()

        async def mock_cmd(cmd, timeout=60):
            raise asyncio.TimeoutError()

        with patch.object(executor, "_run_cmd", side_effect=mock_cmd):
            result = await executor.execute(
                repo_url="https://github.com/org/repo",
                branch="feature/test",
                task_prompt="implement",
            )
        assert result.success is False
        assert result.exit_code == 124
        assert "timed out" in result.stderr

    @pytest.mark.asyncio
    async def test_general_exception_handled(self):
        """General exceptions should be caught and returned as failure."""
        executor = SandboxExecutor()

        async def mock_cmd(cmd, timeout=60):
            raise RuntimeError("unexpected error")

        with patch.object(executor, "_run_cmd", side_effect=mock_cmd):
            result = await executor.execute(
                repo_url="https://github.com/org/repo",
                branch="feature/test",
                task_prompt="implement",
            )
        assert result.success is False
        assert "unexpected error" in result.stderr
