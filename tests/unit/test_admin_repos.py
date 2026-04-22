"""Tests for the Admin Repository CRUD API."""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai_driven.api.admin.router import RepoCreate, RepoUpdate, router


# ---- Fixtures ----


@pytest.fixture
def team_id():
    return uuid.UUID("00000000-0000-0000-0000-000000000001")


@pytest.fixture
def repo_id():
    return uuid.UUID("00000000-0000-0000-0000-000000000002")


@pytest.fixture
def mock_team(team_id):
    team = MagicMock()
    team.id = team_id
    team.name = "test-team"
    team.api_key_hash = hashlib.sha256(b"test-key").hexdigest()
    team.is_active = True
    return team


@pytest.fixture
def mock_repo(team_id, repo_id):
    repo = MagicMock()
    repo.id = repo_id
    repo.team_id = team_id
    repo.full_name = "org/my-repo"
    repo.scm_type = "github"
    repo.default_branch = "main"
    repo.clone_url = "https://github.com/org/my-repo.git"
    repo.is_active = True
    repo.settings_json = {}
    repo.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return repo


def _make_app_and_client(mock_team, mock_session):
    """Wire up a test FastAPI app with mocked DB session and team auth.

    A lightweight middleware is added that mimics what APIKeyMiddleware does:
    it hashes the X-API-Key header and stores it on ``request.state``.
    """
    from starlette.middleware.base import BaseHTTPMiddleware
    from ai_driven.auth.middleware import hash_api_key as _hash

    app = FastAPI()

    class _FakeAuthMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            key = request.headers.get("X-API-Key")
            if key:
                request.state.api_key_hash = _hash(key)
            return await call_next(request)

    app.add_middleware(_FakeAuthMiddleware)
    app.include_router(router, prefix="/api/admin")

    # Override get_session dependency
    async def _fake_session():
        yield mock_session

    from ai_driven.db.postgres.session import get_session
    app.dependency_overrides[get_session] = _fake_session

    client = TestClient(app)
    return app, client


@pytest.fixture
def mock_session():
    session = AsyncMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.add = MagicMock()
    session.get = AsyncMock(return_value=None)
    session.execute = AsyncMock()
    session.refresh = AsyncMock()
    return session


# ---- DTO Validation Tests ----


class TestRepoCreateValidation:

    def test_valid_full_name(self):
        model = RepoCreate(full_name="org/repo", scm_type="github")
        assert model.full_name == "org/repo"

    def test_invalid_full_name_no_slash(self):
        with pytest.raises(ValidationError):
            RepoCreate(full_name="no-slash-here", scm_type="github")

    def test_invalid_full_name_special_chars(self):
        with pytest.raises(ValidationError):
            RepoCreate(full_name="org/repo with spaces", scm_type="github")

    def test_invalid_scm_type(self):
        with pytest.raises(ValidationError):
            RepoCreate(full_name="org/repo", scm_type="gitlab")

    def test_valid_bitbucket(self):
        model = RepoCreate(full_name="org/repo", scm_type="bitbucket")
        assert model.scm_type == "bitbucket"

    def test_defaults(self):
        model = RepoCreate(full_name="org/repo")
        assert model.scm_type == "github"
        assert model.default_branch == "main"


class TestRepoUpdateValidation:

    def test_all_none_is_valid(self):
        model = RepoUpdate()
        assert model.default_branch is None
        assert model.settings_json is None

    def test_partial_update(self):
        model = RepoUpdate(default_branch="develop")
        assert model.default_branch == "develop"


# ---- API Endpoint Tests ----


class TestCreateRepo:

    def test_creates_repo_successfully(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_full_name = AsyncMock(return_value=None)
            MockRepoRepo.return_value.create = AsyncMock(return_value=mock_repo)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.post(
                "/api/admin/repos",
                json={"full_name": "org/my-repo", "scm_type": "github"},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 201
            body = resp.json()
            # CamelModel serializes with camelCase aliases
            assert body.get("fullName", body.get("full_name")) == "org/my-repo"
            assert body.get("scmType", body.get("scm_type")) == "github"
            assert body.get("isActive", body.get("is_active")) is True

    def test_returns_409_on_duplicate(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            # Simulate existing repo with same team_id
            MockRepoRepo.return_value.get_by_full_name = AsyncMock(return_value=mock_repo)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.post(
                "/api/admin/repos",
                json={"full_name": "org/my-repo"},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 409

    def test_returns_401_without_api_key(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=None)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.post(
                "/api/admin/repos",
                json={"full_name": "org/repo"},
            )

            assert resp.status_code == 401

    def test_returns_422_for_invalid_body(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.post(
                "/api/admin/repos",
                json={"full_name": "bad name", "scm_type": "github"},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 422


class TestListRepos:

    def test_list_repos_returns_team_repos(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.list_for_team = AsyncMock(return_value=[mock_repo])

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.get(
                "/api/admin/repos",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 200
            body = resp.json()
            assert body["total"] == 1
            assert body["data"][0].get("fullName", body["data"][0].get("full_name")) == "org/my-repo"

    def test_list_repos_empty(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.list_for_team = AsyncMock(return_value=[])

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.get(
                "/api/admin/repos",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 200
            assert resp.json()["total"] == 0


class TestDeleteRepo:

    def test_soft_deletes_repo(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=mock_repo)
            MockRepoRepo.return_value.soft_delete = AsyncMock()

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.delete(
                f"/api/admin/repos/{mock_repo.id}",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 204
            MockRepoRepo.return_value.soft_delete.assert_awaited_once()

    def test_returns_404_for_nonexistent_repo(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=None)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.delete(
                f"/api/admin/repos/{uuid.uuid4()}",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 404

    def test_returns_404_for_other_teams_repo(self, mock_session, mock_team, mock_repo):
        # Repo belongs to a different team
        mock_repo.team_id = uuid.UUID("99999999-9999-9999-9999-999999999999")

        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=mock_repo)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.delete(
                f"/api/admin/repos/{mock_repo.id}",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 404

    def test_returns_400_for_invalid_uuid(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.delete(
                "/api/admin/repos/not-a-uuid",
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 400


class TestUpdateRepo:

    def test_updates_default_branch(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=mock_repo)
            MockRepoRepo.return_value.update_fields = AsyncMock()

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.put(
                f"/api/admin/repos/{mock_repo.id}",
                json={"default_branch": "develop"},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 200
            MockRepoRepo.return_value.update_fields.assert_awaited_once()

    def test_returns_404_for_nonexistent_repo(self, mock_session, mock_team):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=None)

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.put(
                f"/api/admin/repos/{uuid.uuid4()}",
                json={"default_branch": "develop"},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 404

    def test_no_op_when_body_is_empty(self, mock_session, mock_team, mock_repo):
        with patch("ai_driven.api.admin.router.TeamRepository") as MockTeamRepo, \
             patch("ai_driven.api.admin.router.RepoRepository") as MockRepoRepo:
            MockTeamRepo.return_value.get_by_api_key_hash = AsyncMock(return_value=mock_team)
            MockRepoRepo.return_value.get_by_id = AsyncMock(return_value=mock_repo)
            MockRepoRepo.return_value.update_fields = AsyncMock()

            _, client = _make_app_and_client(mock_team, mock_session)
            resp = client.put(
                f"/api/admin/repos/{mock_repo.id}",
                json={},
                headers={"X-API-Key": "test-key"},
            )

            assert resp.status_code == 200
            # update_fields should NOT be called when there are no fields to update
            MockRepoRepo.return_value.update_fields.assert_not_awaited()
