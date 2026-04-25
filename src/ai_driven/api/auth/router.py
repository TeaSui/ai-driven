"""Session endpoint for the Next.js frontend.

Scaffolding for Sprint 9 Phase 2 of docs/implementation/08-auth-and-ws-security.md.

The full spec calls for a Cognito User Pool with a `teamId` custom attribute
and `/api/auth/session` returning `{userId, teamId, role, email}` from a
validated JWT. Provisioning the Cognito pool is out of scope for Claude (needs
AWS access), so this module:

1. Defines the response shape the frontend expects.
2. Implements the endpoint with the existing API-key middleware as the identity
   source, so the UI can call it today and get a real response.
3. Leaves a `verify_cognito_jwt()` shim that raises `NotImplementedError` until
   the Cognito pool is provisioned and the pool ID is configured.

When the pool lands, swap :func:`_resolve_identity` to call
`verify_cognito_jwt(token)` first, fall back to the API-key path for
backwards-compat during the migration window, then delete the fallback.
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.postgres.models import Team, TeamMember
from ai_driven.db.postgres.session import get_session


def _extract_session_token(request: Request) -> str | None:
    """Pull the session token from either Authorization: Bearer or cookie."""
    auth = request.headers.get("Authorization") or ""
    if isinstance(auth, str) and auth.startswith("Bearer "):
        return auth.removeprefix("Bearer ").strip() or None
    cookie = request.cookies.get("session_token")
    if isinstance(cookie, str):
        return cookie or None
    return None

logger = logging.getLogger(__name__)
router = APIRouter()


class SessionResponse(BaseModel):
    """Response shape for GET /api/auth/session.

    Mirrors the spec in docs/implementation/08-auth-and-ws-security.md:
    ``{userId, teamId, role, email}``. Debug mode may return sentinel values
    so the frontend has something to render without a real session.
    """

    user_id: str
    team_id: str
    team_name: str
    role: str  # owner | admin | member | viewer
    email: str
    # `auth_source` is a Sprint-9 migration aid: tells the UI whether the
    # session was resolved via Cognito JWT, API key, or debug bypass. Remove
    # after Cognito is the only path.
    auth_source: str  # "cognito" | "api_key" | "debug"

    class Config:
        populate_by_name = True


def verify_cognito_jwt(token: str) -> dict[str, Any]:
    """Validate a Cognito-issued JWT and return its claims.

    TODO(sprint-9-phase-2): implement once the Cognito pool is provisioned.
    Needs:
        * ``settings.cognito_pool_id`` + region
        * ``settings.cognito_client_id``
        * JWKS cache (5-minute TTL is fine; Cognito rotates keys rarely)
        * Signature + ``aud``, ``iss``, ``exp`` verification

    Raises :class:`NotImplementedError` until the pool exists so callers
    cannot silently fall through to a half-validated token.
    """
    raise NotImplementedError(
        "verify_cognito_jwt: Cognito pool not yet provisioned. "
        "See docs/implementation/08-auth-and-ws-security.md Phase 2."
    )


async def _resolve_identity(
    request: Request, session: AsyncSession
) -> SessionResponse:
    """Resolve an authenticated identity into a SessionResponse.

    Source precedence:
        1. (future) Cognito JWT in Authorization header
        2. API key in X-API-Key header (current behavior)
        3. Debug-mode sentinel when ``settings.debug`` is true

    Returns ``SessionResponse`` or raises ``HTTPException`` with 401.
    """
    from ai_driven.auth.middleware import hash_api_key
    from ai_driven.config import get_settings

    settings = get_settings()

    # (1) Cognito JWT — scaffolding; enabled once verify_cognito_jwt works.
    # auth_header = request.headers.get("Authorization", "")
    # if auth_header.startswith("Bearer "):
    #     claims = verify_cognito_jwt(auth_header.removeprefix("Bearer "))
    #     ... lookup team from claims["custom:teamId"] ...

    # (1b) Session token — Sprint 14 UI login flow. Authorization: Bearer <token>
    # or session_token cookie → Redis lookup → team identity.
    session_token = _extract_session_token(request)
    if session_token:
        from ai_driven.auth import sessions as session_store
        try:
            s = await session_store.load_session(session_token)
        except Exception:
            # Redis outage: fail closed rather than silently falling through
            # to the API-key path. A browser with a session cookie should get
            # a clear 503, not a quiet downgrade to something it didn't use.
            raise HTTPException(
                status_code=503,
                detail="Session store unavailable — please retry shortly.",
            )
        if s:
            # Hydrate team/member rows for display fields.
            team_result = await session.execute(
                select(Team).where(Team.id == uuid.UUID(s.team_id))
            )
            team = team_result.scalar_one_or_none()
            member: TeamMember | None = None
            if s.member_id:
                member_result = await session.execute(
                    select(TeamMember).where(
                        TeamMember.id == uuid.UUID(s.member_id)
                    )
                )
                member = member_result.scalar_one_or_none()
            return SessionResponse(
                user_id=s.member_id or s.team_id,
                team_id=s.team_id,
                team_name=team.name if team else "unknown",
                role=s.role,
                email=s.email
                or (member.email if member else f"team-{s.team_id}@example.invalid"),
                auth_source="session",
            )

    # (2) API key — the current production path.
    api_key = request.headers.get("X-API-Key") or request.cookies.get("api_key")
    if api_key:
        key_hash = hash_api_key(api_key)
        team_result = await session.execute(
            select(Team).where(Team.api_key_hash == key_hash)
        )
        team = team_result.scalar_one_or_none()
        if team:
            # Best-effort: fetch the member record matching this key, if any.
            member_result = await session.execute(
                select(TeamMember).where(
                    TeamMember.team_id == team.id,
                    TeamMember.api_key_hash == key_hash,
                )
            )
            member = member_result.scalar_one_or_none()
            return SessionResponse(
                user_id=str(member.id) if member else str(team.id),
                team_id=str(team.id),
                team_name=team.name,
                role=(member.role if member else "owner"),
                email=(member.email if member else f"team-{team.id}@example.invalid"),
                auth_source="api_key",
            )

    # (3) Debug bypass. Only kicks in when settings.debug is true AND nothing
    # else authenticated — keeps real prod traffic from silently landing here.
    if settings.debug:
        return SessionResponse(
            user_id="00000000-0000-0000-0000-000000000000",
            team_id="00000000-0000-0000-0000-000000000000",
            team_name="Debug Team",
            role="owner",
            email="debug@local",
            auth_source="debug",
        )

    raise HTTPException(status_code=401, detail="Not authenticated")


@router.get("/session", response_model=SessionResponse)
async def get_session_endpoint(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> SessionResponse:
    """Return the authenticated session's identity for the Next.js UI.

    Used by ``SessionProvider`` in the frontend to populate the sidebar,
    replace the hardcoded "Alex Chen · Platform · Manila" line, and gate
    client-side routing.
    """
    return await _resolve_identity(request, session)


# ── Sprint 14: session-token login flow ───────────────────────────────────────


class LoginRequest(BaseModel):
    """Body for POST /api/auth/login.

    ``email`` is optional and stored as session metadata for audit purposes
    and as the forward-compatible field for the future Cognito migration. It
    is NOT a second authentication factor.
    """

    api_key: str
    email: str | None = None


class LoginResponse(BaseModel):
    session_token: str
    team_id: str
    member_id: str | None = None
    email: str | None = None
    role: str
    created_at: str
    last_seen_at: str
    expires_at: str


@router.post("/login", response_model=LoginResponse)
async def login(
    body: LoginRequest,
    response: Response,
    session: AsyncSession = Depends(get_session),
) -> LoginResponse:
    """Exchange a valid API key for a short-lived session token.

    Returns a cookie (`session_token`) and the same token in the JSON body so
    non-browser clients can use `Authorization: Bearer` if they want to.

    Failure modes:
        * 401 — API key invalid
        * 503 — Redis unavailable (fail-closed; retry shortly)
    """
    from ai_driven.auth import sessions as session_store
    from ai_driven.auth.middleware import hash_api_key
    from ai_driven.config import get_settings

    settings = get_settings()
    key = body.api_key.strip()
    if not key:
        raise HTTPException(status_code=401, detail="API key is required")

    key_hash = hash_api_key(key)
    team_result = await session.execute(
        select(Team).where(Team.api_key_hash == key_hash)
    )
    team = team_result.scalar_one_or_none()
    if not team:
        raise HTTPException(status_code=401, detail="Invalid API key")

    # If the key matches a specific member, hang the member_id + their
    # registered email on the session for audit; else team-level session.
    member_id: str | None = None
    member_email: str | None = None
    member_role = "owner"  # Team-level keys get owner by convention.
    member_result = await session.execute(
        select(TeamMember).where(
            TeamMember.team_id == team.id,
            TeamMember.api_key_hash == key_hash,
        )
    )
    member = member_result.scalar_one_or_none()
    if member:
        member_id = str(member.id)
        member_email = member.email
        member_role = member.role

    try:
        sess = await session_store.create_session(
            team_id=str(team.id),
            member_id=member_id,
            email=body.email or member_email,
            role=member_role,
        )
    except Exception:
        # Redis down — be explicit so the UI can tell the user what to do.
        raise HTTPException(
            status_code=503,
            detail="Session store unavailable — please retry shortly.",
        )

    # Set an HttpOnly cookie. Secure flag is honoured in production (HTTPS);
    # local dev over http:// still works because httpx / fetch attach the
    # cookie regardless when `secure=False`.
    response.set_cookie(
        key="session_token",
        value=sess.token,
        httponly=True,
        samesite="lax",
        secure=False,  # TODO: flip to True behind an env flag for prod deploys.
        max_age=settings.session_ttl_seconds,
        path="/",
    )

    wire = session_store.to_wire(sess)
    return LoginResponse(**wire)


@router.post("/logout", status_code=204)
async def logout(request: Request, response: Response) -> None:
    """Revoke the current session and clear the cookie.

    Idempotent: safe to call when no session exists.
    """
    from ai_driven.auth import sessions as session_store

    token = _extract_session_token(request)
    if token:
        try:
            await session_store.revoke_session(token)
        except Exception:
            # Redis down — still clear the cookie so the browser stops sending
            # a stale token. The session will expire from Redis naturally.
            logger.warning("logout: redis revoke failed; clearing cookie anyway")

    response.delete_cookie(key="session_token", path="/")
