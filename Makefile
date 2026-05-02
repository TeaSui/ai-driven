.PHONY: install dev test test-all test-cov lint format run worker frontend-dev docker-up docker-down clean

install:
	cd backend && pip install -e .

dev:
	cd backend && pip install -e ".[dev]"

test:
	cd backend && pytest tests/unit -v --tb=short

test-all:
	cd backend && pytest tests/ -v --tb=short

test-cov:
	cd backend && pytest tests/unit -v --cov=ai_driven --cov-report=term-missing

lint:
	cd backend && ruff check src/ tests/
	cd backend && mypy src/ai_driven/

format:
	cd backend && ruff format src/ tests/
	cd backend && ruff check --fix src/ tests/

run:
	cd backend && AI_DRIVEN_DEBUG=true uvicorn ai_driven.main:app --host 0.0.0.0 --port 8888 --reload

worker:
	cd backend && python -m ai_driven.worker

frontend-dev:
	cd frontend && npm run dev

docker-up:
	cd docker && docker compose up -d

docker-down:
	cd docker && docker compose down

clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete 2>/dev/null || true
	rm -rf backend/.mypy_cache backend/.ruff_cache backend/.pytest_cache backend/htmlcov backend/.coverage
