.PHONY: install dev test test-all test-cov lint format run run-live worker frontend-dev docker-up docker-down clean

# Pinned to the repo-root venv so `python` / `pip` / `pytest` resolve even
# when the user's shell PATH doesn't have the venv activated.
PY      := $(CURDIR)/.venv/bin/python
PIP     := $(CURDIR)/.venv/bin/pip
PYTEST  := $(CURDIR)/.venv/bin/pytest
RUFF    := $(CURDIR)/.venv/bin/ruff
MYPY    := $(CURDIR)/.venv/bin/mypy
UVICORN := $(CURDIR)/.venv/bin/uvicorn

install:
	cd backend && $(PIP) install -e .

dev:
	cd backend && $(PIP) install -e ".[dev]"

test:
	cd backend && $(PYTEST) tests/unit -v --tb=short

test-all:
	cd backend && $(PYTEST) tests/ -v --tb=short

test-cov:
	cd backend && $(PYTEST) tests/unit -v --cov=ai_driven --cov-report=term-missing

lint:
	cd backend && $(RUFF) check src/ tests/
	cd backend && $(MYPY) src/ai_driven/

format:
	cd backend && $(RUFF) format src/ tests/
	cd backend && $(RUFF) check --fix src/ tests/

# Debug-mode run (auth bypass + inline task processing). Use for frontend
# UI iteration where you don't care about real auth flows.
run:
	cd backend && set -a && . ./.env.local && set +a && AI_DRIVEN_DEBUG=true $(UVICORN) ai_driven.main:app --host 0.0.0.0 --port 8888 --reload

# Live-mode run (auth enforced, orchestrator_mode respects .env.local).
# Use this for real end-to-end flows — matches what production sees.
run-live:
	cd backend && set -a && . ./.env.local && set +a && $(UVICORN) ai_driven.main:app --host 127.0.0.1 --port 8888

worker:
	cd backend && set -a && . ./.env.local && set +a && $(PY) -m ai_driven.worker

frontend-dev:
	cd frontend && npm run dev

docker-up:
	cd ops/docker && docker compose up -d

docker-down:
	cd ops/docker && docker compose down

clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete 2>/dev/null || true
	rm -rf backend/.mypy_cache backend/.ruff_cache backend/.pytest_cache backend/htmlcov backend/.coverage
