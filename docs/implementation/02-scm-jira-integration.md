# Sprint 2: SCM & Jira Integration

**Status: COMPLETE**

## Goal

Make the SCM clients (GitHub + Bitbucket) and Jira client production-ready with retry logic, error handling, pagination, and rate limiting. Wire webhook handlers to actually create tasks. Add integration tests.

## Prerequisites

- Sprint 1 complete (all checkboxes checked, 18 tests passing)
- `.env` file created from `.env.example` (at minimum: Jira, GitHub or Bitbucket creds)
- Docker compose running for PostgreSQL (`make docker-up`)

---

## Tasks

### 2.1 SCM Client Hardening — GitHub

**File: `src/ai_driven/scm/github_client.py`**

- [x] Add retry logic with `tenacity` (retry on 429/5xx, exponential backoff)
- [x] Add pagination for `list_files` (GitHub returns max 1000 items)
- [x] Add rate limit awareness (read `X-RateLimit-Remaining` header, sleep when low)
- [x] Handle GitHub App JWT auth (generate JWT from app_id + private_key, exchange for installation token)
- [x] Add `get_pull_request()` method — fetch a single PR by number
- [x] Add `list_pull_requests()` method — list PRs with state/sort filters
- [x] Add `create_or_update_file()` method — commit a file change directly via API
- [x] Add `get_commit_diff()` method — for push event handling
- [x] Handle error responses properly: 404 (not found), 403 (rate limited), 422 (validation)
- [x] Unit tests with mocked httpx responses for each method

**Key implementation detail — GitHub App auth:**
```python
# In github_client.py
import jwt, time

def _generate_jwt(app_id: str, private_key: str) -> str:
    payload = {"iat": int(time.time()) - 60, "exp": int(time.time()) + 600, "iss": app_id}
    return jwt.encode(payload, private_key, algorithm="RS256")

async def _get_installation_token(self, installation_id: str) -> str:
    jwt_token = _generate_jwt(self._app_id, self._private_key)
    resp = await self._http.post(
        f"/app/installations/{installation_id}/access_tokens",
        headers={"Authorization": f"Bearer {jwt_token}"},
    )
    return resp.json()["token"]
```

### 2.2 SCM Client Hardening — Bitbucket

**File: `src/ai_driven/scm/bitbucket_client.py`**

- [x] Add retry logic with `tenacity` (retry on 429/5xx)
- [x] Add pagination for `list_files` (Bitbucket uses `next` URLs)
- [x] Add OAuth2 client credentials auth (exchange client_id/secret for access token, refresh on expiry)
- [x] Add `get_pull_request()` method — fetch a single PR
- [x] Add `list_pull_requests()` method — list PRs with filters
- [x] Add `create_or_update_file()` method — commit via API
- [x] Handle Bitbucket-specific error codes (400, 403, 404)
- [x] Unit tests with mocked httpx responses

### 2.3 SCM Factory

**New file: `src/ai_driven/scm/factory.py`**

- [x] Create `get_scm_client(scm_type, credentials)` factory function
- [x] Returns `GitHubClient` or `BitbucketClient` based on `scm_type`
- [ ] Credential lookup from team settings or Secrets Manager
- [ ] Cache clients per team to avoid re-auth

```python
# Implementation pattern
from ai_driven.scm.interface import SCMClient
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.bitbucket_client import BitbucketClient

_client_cache: dict[str, SCMClient] = {}

async def get_scm_client(scm_type: str, team_id: str, credentials: dict) -> SCMClient:
    cache_key = f"{team_id}:{scm_type}"
    if cache_key not in _client_cache:
        if scm_type == "github":
            _client_cache[cache_key] = GitHubClient(token=credentials["token"])
        elif scm_type == "bitbucket":
            _client_cache[cache_key] = BitbucketClient(
                username=credentials["username"],
                app_password=credentials["app_password"],
            )
    return _client_cache[cache_key]
```

### 2.4 Jira Client Hardening

**File: `src/ai_driven/jira/client.py`**

- [x] Add retry logic with `tenacity`
- [x] Add pagination for comments (Jira paginates comment lists)
- [x] Improve ADF→text extraction (handle lists, code blocks, tables, mentions)
- [x] Add `search_issues(jql)` method — JQL search for finding related issues
- [x] Add `get_issue_links()` method — find linked issues (blocks, relates to, etc.)
- [x] Add `assign_issue()` method — assign to a user
- [x] Handle Jira custom field mapping (acceptance criteria field ID varies per Jira instance)
- [x] Make custom field IDs configurable in team settings
- [x] Unit tests with mocked httpx responses

**Key implementation detail — configurable custom fields:**
```python
# In config.py, add:
jira_acceptance_criteria_field: str = "customfield_10028"

# In jira/client.py, use:
ac_field = settings.jira_acceptance_criteria_field
acceptance_criteria = self._extract_text(fields.get(ac_field, {}))
```

### 2.5 Webhook Handlers — Wire to Task Creation

**Files: `src/ai_driven/api/webhooks/github.py`, `bitbucket.py`, `jira.py`**

- [x] GitHub `_handle_pr_event`: On PR opened → create Review task in DB → enqueue to SQS
- [x] GitHub `_handle_issue_comment`: Parse `/ai-code`, `/ai-review` slash commands → create appropriate task
- [x] Bitbucket `_handle_pr_created`: Same as GitHub PR opened flow
- [x] Bitbucket `_handle_pr_comment`: Parse slash commands
- [x] Jira `_handle_issue_updated`: On transition to "Ready for Dev" → create Coding task → enqueue
- [x] All handlers: Use `WebhookEventStore` for deduplication (check event_id before processing)
- [x] All handlers: Resolve team from webhook payload (map repo → team via Repository table)

**Key implementation detail — webhook → task flow:**
```python
# In github.py _handle_pr_event:
async def _handle_pr_event(payload: dict, session: AsyncSession) -> None:
    if payload.get("action") not in ("opened", "synchronize"):
        return
    pr = payload["pull_request"]
    repo_name = payload["repository"]["full_name"]

    # Deduplicate
    event_store = WebhookEventStore()
    event_id = f"gh-pr-{repo_name}-{pr['number']}-{payload['action']}"
    if not event_store.record_event(event_id, "github", "pull_request", payload):
        return  # Duplicate

    # Find team + repo
    repo_record = await session.execute(
        select(Repository).where(Repository.full_name == repo_name)
    )
    repo = repo_record.scalar_one_or_none()
    if not repo:
        return

    # Create task
    task_repo = TaskRepository(session)
    task = await task_repo.create(
        team_id=repo.team_id, repo_id=repo.id,
        agent_type="review", status="queued",
        title=f"Review PR #{pr['number']}: {pr['title']}",
        input_data={"repo_full_name": repo_name, "pr_number": pr["number"]},
    )

    # Enqueue
    producer = TaskProducer()
    await producer.enqueue_task(str(task.id), "review", task.input_data)
```

### 2.6 Webhook Signature Verification

- [x] GitHub: Already implemented (HMAC-SHA256) — verify it works with real webhooks
- [ ] Bitbucket: Add IP allowlist verification OR webhook secret verification
- [ ] Jira: Add webhook secret verification (Jira Cloud supports secrets in webhook config)
- [ ] DataDog: Add shared secret header verification

### 2.7 Database — Alembic Migrations

- [x] Initialize Alembic: `alembic init alembic`
- [x] Configure `alembic/env.py` to use async engine and import models
- [x] Generate initial migration: `alembic revision --autogenerate -m "initial schema"`
- [x] Run migration: `alembic upgrade head`
- [ ] Add migration to Docker entrypoint (run on startup)

**File: `alembic/env.py` key changes:**
```python
from ai_driven.config import get_settings
from ai_driven.db.postgres.models import Base

settings = get_settings()
config.set_main_option("sqlalchemy.url", settings.postgres_url.replace("+asyncpg", ""))
target_metadata = Base.metadata
```

### 2.8 Integration Tests — SCM

**New file: `tests/integration/test_github_client.py`**

- [x] Test `list_files` with mocked HTTP (using `respx` or `httpx.MockTransport`)
- [x] Test `read_file` — verify base64 decoding
- [x] Test `create_pull_request` — verify request body
- [x] Test `get_pr_diff` — verify diff parsing
- [x] Test `post_pr_comment` — inline vs general comment
- [x] Test error handling (404, 403 rate limit, 5xx retry)
- [x] Test GitHub App JWT generation

**New file: `tests/integration/test_bitbucket_client.py`**

- [x] Test `list_files` with pagination
- [x] Test `read_file`
- [x] Test `create_pull_request`
- [x] Test `get_pr_diff` — verify unified diff parsing
- [x] Test `post_pr_comment` — inline with Bitbucket format
- [x] Test error handling

### 2.9 Integration Tests — Jira

**New file: `tests/integration/test_jira_client.py`**

- [x] Test `get_issue` — verify ADF parsing for description and acceptance criteria
- [x] Test `get_transitions` — verify transition list parsing
- [x] Test `transition_issue` — verify correct transition ID lookup
- [x] Test `add_comment` — verify ADF body construction
- [ ] Test error handling (issue not found, no permission, invalid transition)

### 2.10 Integration Tests — Webhooks

**New file: `tests/integration/test_webhooks.py`**

- [x] Test GitHub webhook with valid signature → 200, task created
- [x] Test GitHub webhook with invalid signature → 401
- [x] Test GitHub PR opened → review task queued
- [x] Test GitHub `/ai-code PROJ-123` comment → coding task queued
- [x] Test Bitbucket PR created → review task queued
- [x] Test Jira issue transition to "Ready for Dev" → coding task queued
- [x] Test duplicate webhook → second request ignored (dedup)

### 2.11 FastAPI Test Client Setup

**New file: `tests/conftest.py`**

- [x] Create test fixtures: `test_client` (FastAPI TestClient), `test_session` (async SQLAlchemy session)
- [ ] Create in-memory SQLite or test PostgreSQL via TestContainers
- [x] Create test team and API key fixtures
- [x] Override FastAPI dependencies for testing

```python
import pytest
from httpx import AsyncClient, ASGITransport
from ai_driven.main import app
from ai_driven.db.postgres.session import get_session

@pytest.fixture
async def client(test_session):
    app.dependency_overrides[get_session] = lambda: test_session
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()
```

---

## Verification

```bash
# Unit tests still pass
pytest tests/unit -v
# Expected: 18+ passed (new unit tests added)

# Integration tests pass
pytest tests/integration -v
# Expected: All webhook + client tests pass

# Manual smoke test — start API and hit health
docker compose -f docker/docker-compose.yml up -d postgres
source .venv/bin/activate
uvicorn ai_driven.main:app --reload
curl http://localhost:8888/health
# Expected: {"status":"healthy","version":"0.1.0"}

# Manual smoke test — send fake GitHub webhook
curl -X POST http://localhost:8888/webhooks/github \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" \
  -d '{"action":"opened","pull_request":{"number":1,"title":"Test PR"},"repository":{"full_name":"test/repo"}}'
# Expected: {"status":"accepted"}

# Database migration
alembic upgrade head
# Expected: Tables created in PostgreSQL
```

## Files Created/Modified This Sprint

| Action | File |
|--------|------|
| Modify | `src/ai_driven/scm/github_client.py` |
| Modify | `src/ai_driven/scm/bitbucket_client.py` |
| Create | `src/ai_driven/scm/factory.py` |
| Modify | `src/ai_driven/jira/client.py` |
| Modify | `src/ai_driven/config.py` |
| Modify | `src/ai_driven/api/webhooks/github.py` |
| Modify | `src/ai_driven/api/webhooks/bitbucket.py` |
| Modify | `src/ai_driven/api/webhooks/jira.py` |
| Modify | `src/ai_driven/api/webhooks/datadog.py` |
| Configure | `alembic/env.py` |
| Create | `alembic/versions/001_initial_schema.py` |
| Create | `tests/conftest.py` |
| Create | `tests/integration/test_github_client.py` |
| Create | `tests/integration/test_bitbucket_client.py` |
| Create | `tests/integration/test_jira_client.py` |
| Create | `tests/integration/test_webhooks.py` |
