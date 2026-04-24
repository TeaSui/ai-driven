.PHONY: install dev test lint format run docker-up docker-down

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

test:
	pytest tests/unit -v --tb=short

test-all:
	pytest tests/ -v --tb=short

test-cov:
	pytest tests/unit -v --cov=ai_driven --cov-report=term-missing

lint:
	ruff check src/ tests/
	mypy src/ai_driven/

format:
	ruff format src/ tests/
	ruff check --fix src/ tests/

run:
	AI_DRIVEN_DEBUG=true uvicorn ai_driven.main:app --host 0.0.0.0 --port 8888 --reload

docker-up:
	cd docker && docker compose up -d

docker-down:
	cd docker && docker compose down

clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete 2>/dev/null || true
	rm -rf .mypy_cache .ruff_cache .pytest_cache htmlcov .coverage
