"""Shared fixtures and guards for the eval suite.

Eval tests are opt-in. They burn real Anthropic API tokens (one full reviewer
run per case + a Haiku grader call per dimension) and are not part of the
default `pytest` run.

To opt in:
    pytest -m eval tests/evals
"""

from __future__ import annotations

import os

import pytest


def pytest_collection_modifyitems(config, items):
    """Skip eval-marked tests unless the marker is explicitly selected.

    Two ways to enable:
      - `pytest -m eval`
      - environment variable `RUN_EVALS=1`
    """
    selected_markers = config.getoption("-m") or ""
    explicit = "eval" in selected_markers
    env = os.getenv("RUN_EVALS") in ("1", "true", "yes")
    if explicit or env:
        return

    skip_eval = pytest.mark.skip(
        reason="eval suite is opt-in: run `pytest -m eval` or set RUN_EVALS=1",
    )
    for item in items:
        if "eval" in item.keywords:
            item.add_marker(skip_eval)
