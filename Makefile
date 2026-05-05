.PHONY: install dev test test-all test-cov lint format run worker frontend-dev frontend-clean docker-up docker-down clean

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

# Real-mode run (auth enforced, SQS-backed task dispatch, matches what
# production sees). The debug auth-bypass mode has been removed — any
# request without a valid X-API-Key or session cookie gets 401.
run:
	cd backend && set -a && . ./.env.local && set +a && $(UVICORN) ai_driven.main:app --host 127.0.0.1 --port 8888 --reload

worker:
	cd backend && set -a && . ./.env.local && set +a && $(PY) -m ai_driven.worker

frontend-dev:
	cd frontend && npm run dev

# Kill any running dev server, nuke the build cache, and start fresh.
# Use this whenever chunks 404 or a hydration/module-not-found loop
# appears in the dev console — it's the Next.js dev cache getting out
# of sync with file-system renames from PR-level refactors.
frontend-clean:
	@pkill -f "next dev --port 3000" 2>/dev/null || true
	@pkill -f "next-server" 2>/dev/null || true
	@sleep 1
	@rm -rf frontend/.next frontend/node_modules/.cache
	@echo "Cleaned. Run 'make frontend-dev' to restart."

docker-up:
	cd ops/docker && docker compose up -d

docker-down:
	cd ops/docker && docker compose down

clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete 2>/dev/null || true
	rm -rf backend/.mypy_cache backend/.ruff_cache backend/.pytest_cache backend/htmlcov backend/.coverage
