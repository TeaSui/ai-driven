"""Admin /me/connections endpoints — list / test / disconnect (PR 1).

Caller-scoped: every query filters by ``team_member_id = member.member_id``
so an admin on team A cannot see or revoke connections on team B.

Tokens are NEVER returned in responses — only the connection metadata
(scmUsername, scope, expiresAt, ...). The plaintext token is held only
long enough to call the provider's revoke or ``/user`` endpoint.
"""

from __future__ import annotations

import logging
import uuid as _uuid
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import delete, select, update as sql_update
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.api._shared import CamelModel
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.auth.scm_tokens import decrypt_token
from ai_driven.config import get_settings
from ai_driven.db.postgres.models import UserScmConnection
from ai_driven.db.postgres.session import get_session

logger = logging.getLogger(__name__)

router = APIRouter()


_VALID_PROVIDERS = {"github", "bitbucket"}
_GITHUB_USER_URL = "https://api.github.com/user"
_BITBUCKET_USER_URL = "https://api.bitbucket.org/2.0/user"


class ConnectionResponse(CamelModel):
    id: str
    provider: str
    scmUsername: str
    scmEmail: str | None = None
    scope: str
    expiresAt: str | None = None
    connectedAt: str
    lastUsedAt: str | None = None


def _error(status: int, code: str, message: str, details: list | None = None) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "error": {
                "code": code,
                "message": message,
                "details": details or [],
            }
        },
    )


def _to_response(row: UserScmConnection) -> ConnectionResponse:
    return ConnectionResponse(
        id=str(row.id),
        provider=row.provider,
        scmUsername=row.scm_username,
        scmEmail=row.scm_email,
        scope=row.scope,
        expiresAt=row.expires_at.isoformat() if row.expires_at else None,
        connectedAt=row.connected_at.isoformat() if row.connected_at else "",
        lastUsedAt=row.last_used_at.isoformat() if row.last_used_at else None,
    )


# ---------------------------------------------------------------------------
# GET /me/connections
# ---------------------------------------------------------------------------


@router.get("/me/connections", response_model=list[ConnectionResponse])
async def list_my_connections(
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> list[ConnectionResponse]:
    """List the caller's own SCM connections. Never returns tokens."""
    result = await session.execute(
        select(UserScmConnection)
        .where(UserScmConnection.team_member_id == _uuid.UUID(member.member_id))
        .order_by(UserScmConnection.provider)
    )
    rows = list(result.scalars().all())
    return [_to_response(r) for r in rows]


# ---------------------------------------------------------------------------
# DELETE /me/connections/{provider}
# ---------------------------------------------------------------------------


async def _revoke_github(client_id: str, client_secret: str, token: str) -> None:
    """Best-effort revoke of a GitHub OAuth token.

    Uses HTTP Basic ``{client_id}:{client_secret}`` auth — the endpoint
    rejects a request signed with the user token itself (by design).
    Swallows errors but logs loudly; we still delete the local row.
    """
    url = f"https://api.github.com/applications/{client_id}/token"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.request(
                "DELETE",
                url,
                auth=(client_id, client_secret),
                json={"access_token": token},
                headers={"Accept": "application/vnd.github+json"},
            )
            # 204 expected on success, 404 if already revoked.
            if resp.status_code not in (204, 404):
                logger.warning(
                    "github_revoke_unexpected_status status=%s body=%s",
                    resp.status_code, resp.text[:200],
                )
    except Exception as ex:
        logger.warning("github_revoke_failed err=%s", ex)


@router.delete("/me/connections/{provider}")
async def delete_my_connection(
    provider: str,
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> JSONResponse:
    """Revoke + delete the caller's connection for ``provider``."""
    if provider not in _VALID_PROVIDERS:
        return _error(400, "PROVIDER_NOT_SUPPORTED", f"Unknown provider: {provider}")

    res = await session.execute(
        select(UserScmConnection).where(
            UserScmConnection.team_member_id == _uuid.UUID(member.member_id),
            UserScmConnection.provider == provider,
        )
    )
    row = res.scalar_one_or_none()
    if row is None:
        return _error(404, "CONNECTION_NOT_FOUND", f"No {provider} connection for this member.")

    settings = get_settings()
    # Best-effort provider-side revoke BEFORE deleting the row. Keeps
    # the audit trail honest if the provider call hangs.
    if provider == "github":
        if settings.github_oauth_client_id and settings.github_oauth_client_secret.get_secret_value():
            try:
                plaintext = decrypt_token(row.access_token_enc)
                await _revoke_github(
                    settings.github_oauth_client_id,
                    settings.github_oauth_client_secret.get_secret_value(),
                    plaintext,
                )
            except Exception as ex:
                # Decrypt failure or anything unexpected — log and move on.
                logger.warning("github_revoke_skipped err=%s", ex)
    # Bitbucket has no OAuth-consumer token-revocation endpoint equivalent
    # to GitHub's. Skip provider-side revoke; the local row delete is
    # sufficient for our attribution model. User can revoke manually at
    # https://bitbucket.org/account/settings/app-authorizations/ .

    await session.execute(
        delete(UserScmConnection).where(UserScmConnection.id == row.id)
    )
    await session.commit()

    return JSONResponse(
        status_code=200,
        content={
            "data": {"provider": provider, "deleted": True},
            "meta": {"timestamp": datetime.now(timezone.utc).isoformat()},
        },
    )


# ---------------------------------------------------------------------------
# POST /me/connections/{provider}/test
# ---------------------------------------------------------------------------


async def _test_github(token: str) -> tuple[bool, str, dict[str, Any]]:
    """Return (ok, reason, user_json). reason is expired|revoked|unknown when !ok."""
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                _GITHUB_USER_URL,
                headers={
                    "Authorization": f"token {token}",
                    "Accept": "application/vnd.github+json",
                },
            )
    except Exception:
        return False, "unknown", {}
    if resp.status_code == 200:
        return True, "", resp.json()
    if resp.status_code == 401:
        return False, "revoked", {}
    if resp.status_code == 403:
        return False, "revoked", {}
    return False, "unknown", {}


async def _test_bitbucket(token: str) -> tuple[bool, str, dict[str, Any]]:
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                _BITBUCKET_USER_URL,
                headers={"Authorization": f"Bearer {token}"},
            )
    except Exception:
        return False, "unknown", {}
    if resp.status_code == 200:
        return True, "", resp.json()
    if resp.status_code == 401:
        return False, "expired", {}
    if resp.status_code == 403:
        return False, "revoked", {}
    return False, "unknown", {}


@router.post("/me/connections/{provider}/test")
async def test_my_connection(
    provider: str,
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> JSONResponse:
    """Verify the stored token still works by hitting ``/user`` on the provider."""
    if provider not in _VALID_PROVIDERS:
        return _error(400, "PROVIDER_NOT_SUPPORTED", f"Unknown provider: {provider}")

    res = await session.execute(
        select(UserScmConnection).where(
            UserScmConnection.team_member_id == _uuid.UUID(member.member_id),
            UserScmConnection.provider == provider,
        )
    )
    row = res.scalar_one_or_none()
    if row is None:
        return _error(404, "CONNECTION_NOT_FOUND", f"No {provider} connection for this member.")

    try:
        plaintext = decrypt_token(row.access_token_enc)
    except Exception as ex:
        logger.warning("decrypt_failed_on_test provider=%s err=%s", provider, type(ex).__name__)
        return JSONResponse(
            status_code=400,
            content={
                "data": {"ok": False, "reason": "unknown", "provider": provider},
                "meta": {"timestamp": datetime.now(timezone.utc).isoformat()},
            },
        )

    if provider == "github":
        ok, reason, user_json = await _test_github(plaintext)
    else:
        ok, reason, user_json = await _test_bitbucket(plaintext)

    if ok:
        # Bump last_used_at so dashboards reflect a real touch.
        await session.execute(
            sql_update(UserScmConnection)
            .where(UserScmConnection.id == row.id)
            .values(last_used_at=datetime.now(timezone.utc))
        )
        await session.commit()

        if provider == "github":
            scm_username = user_json.get("login", row.scm_username)
            scm_email = user_json.get("email", row.scm_email)
        else:
            scm_username = user_json.get("username") or user_json.get("nickname") or row.scm_username
            scm_email = row.scm_email
        return JSONResponse(
            status_code=200,
            content={
                "data": {
                    "ok": True,
                    "provider": provider,
                    "scmUsername": scm_username,
                    "scmEmail": scm_email,
                },
                "meta": {"timestamp": datetime.now(timezone.utc).isoformat()},
            },
        )

    return JSONResponse(
        status_code=400,
        content={
            "data": {"ok": False, "reason": reason, "provider": provider},
            "meta": {"timestamp": datetime.now(timezone.utc).isoformat()},
        },
    )
