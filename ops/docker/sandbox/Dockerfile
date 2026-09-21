# Sandbox image for CodingAgent's execute_in_sandbox tool.
#
# Used via `docker run --rm --network=none ai-driven-sandbox:latest bash -c "<cmd>"`.
# CodingAgent uses this to run tests, linters, and shell inspection during a
# coding run. The --network=none isolation guarantees the agent cannot reach
# the internet or the host from inside the sandbox — only the mounted
# workspace is writable.
#
# Build:
#   docker build -t ai-driven-sandbox:latest -f docker/sandbox/Dockerfile .
#
# Verify:
#   docker run --rm ai-driven-sandbox:latest python3 --version
#   docker run --rm ai-driven-sandbox:latest git --version
#
# Set AI_DRIVEN_SANDBOX_MODE=docker in .env.local to activate.

FROM python:3.11-slim

# System deps: git for clone/diff, curl for Claude Code CLI install, common
# POSIX tools for shell commands the coding agent typically runs.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        curl \
        ca-certificates \
        bash \
        jq \
        tree \
        make \
    && rm -rf /var/lib/apt/lists/*

# Node.js (LTS) — required for the Claude Code CLI `claude` command that
# the `_run_in_docker` path invokes on full-run executions. The shell-only
# `run_command` path doesn't need it but bundling it keeps the image
# versatile.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Common Python tools the agent may want to invoke on test runs
RUN pip install --no-cache-dir pytest ruff mypy

# Non-root user so sandbox processes can't chmod /root into existence on
# the host's mounted workspace
RUN useradd -m -s /bin/bash sandbox
USER sandbox

WORKDIR /workspace

# Provide a default command for smoke tests. Real invocations override
# this with `bash -c "<command>"` or `claude -p "<prompt>"`.
CMD ["bash"]
