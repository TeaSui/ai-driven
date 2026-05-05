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
from sqlalchemy import func, select
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


from ai_driven.api._shared import CamelModel


class SessionResponse(CamelModel):
    """Response shape for GET /api/auth/session.

    Serialized as camelCase: ``{userId, teamId, teamName, role, email, authSource}``.
    Mirrors the spec in docs/implementation/08-auth-and-ws-security.md.
    Debug mode may return sentinel values so the frontend has something
    to render without a real session.
    """

    userId: str
    teamId: str
    teamName: str
    role: str  # owner | admin | member | viewer
    email: str
    # `authSource` is a Sprint-9 migration aid: tells the UI whether the
    # session was resolved via Cognito JWT, API key, or debug bypass. Remove
    # after Cognito is the only path.
    authSource: str  # "cognito" | "api_key" | "debug"


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
        2. Session token (browser UI) via cookie or Bearer header
        3. API key in X-API-Key header

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
                userId=s.member_id or s.team_id,
                teamId=s.team_id,
                teamName=team.name if team else "unknown",
                role=s.role,
                email=s.email
                or (member.email if member else f"team-{s.team_id}@example.invalid"),
                authSource="session",
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
                userId=str(member.id) if member else str(team.id),
                teamId=str(team.id),
                teamName=team.name,
                role=(member.role if member else "owner"),
                email=(member.email if member else f"team-{team.id}@example.invalid"),
                authSource="api_key",
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


class LoginRequest(CamelModel):
    """Body for POST /api/auth/login.

    ``email`` is REQUIRED and must match a ``team_members`` row whose
    ``team_id`` is owned by the team the ``apiKey`` authenticates. This
    binds every session to a specific human so audit logs and
    ``require_admin`` have a real member_id to attribute actions to.

    The API key proves "you belong to this team." The email proves
    "you are this member of that team." Without both, you get 401.
    """

    apiKey: str
    email: str


class LoginResponse(CamelModel):
    """Response shape for POST /api/auth/login.

    Serialized as camelCase: ``{sessionToken, teamId, memberId, email,
    role, createdAt, lastSeenAt, expiresAt}``. Consistent with every
    other API response on the platform (see api/_shared.py).

    ``memberId`` and ``email`` are non-null because login now requires a
    matching team_members row.
    """

    sessionToken: str
    teamId: str
    memberId: str
    email: str
    role: str
    createdAt: str
    lastSeenAt: str
    expiresAt: str


@router.post("/login", response_model=LoginResponse)
async def login(
    body: LoginRequest,
    response: Response,
    session: AsyncSession = Depends(get_session),
) -> LoginResponse:
    """Exchange a valid (apiKey, email) pair for a short-lived session token.

    Returns a cookie (`session_token`) and the same token in the JSON body so
    non-browser clients can use `Authorization: Bearer` if they want to.

    Auth rule: the API key must resolve to a team, AND the email must
    identify an active team_member belonging to that SAME team. Either
    missing or mismatched → 401 with a generic "Invalid credentials"
    (we do not reveal which half failed, per standard auth practice).

    Failure modes:
        * 401 — API key invalid, email not registered to the team, or
          the matched member is inactive
        * 503 — Redis unavailable (fail-closed; retry shortly)
    """
    from ai_driven.auth import sessions as session_store
    from ai_driven.auth.middleware import hash_api_key
    from ai_driven.config import get_settings

    settings = get_settings()
    key = body.apiKey.strip()
    email = (body.email or "").strip().lower()
    if not key or not email:
        raise HTTPException(status_code=401, detail="Invalid credentials")

    key_hash = hash_api_key(key)
    team_result = await session.execute(
        select(Team).where(
            Team.api_key_hash == key_hash,
            Team.is_active.is_(True),
        )
    )
    team = team_result.scalar_one_or_none()
    if not team:
        raise HTTPException(status_code=401, detail="Invalid credentials")

    # Email MUST identify an active team_member on THIS team. The api_key
    # proved the caller belongs to the team; email proves *which* member.
    member_result = await session.execute(
        select(TeamMember).where(
            TeamMember.team_id == team.id,
            func.lower(TeamMember.email) == email,
            TeamMember.is_active.is_(True),
        )
    )
    member = member_result.scalar_one_or_none()
    if member is None:
        raise HTTPException(status_code=401, detail="Invalid credentials")

    member_id = str(member.id)
    member_email = member.email
    member_role = member.role

    try:
        sess = await session_store.create_session(
            team_id=str(team.id),
            member_id=member_id,
            email=member_email,
            role=member_role,
        )
    except Exception:
        # Redis down — be explicit so the UI can tell the user what to do.
        raise HTTPException(
            status_code=503,
            detail="Session store unavailable — please retry shortly.",
        )

    # Set an HttpOnly cookie. Secure flag is on everywhere except local HTTP
    # dev — dev/staging/prod all run behind HTTPS, so the cookie must not be
    # transmittable over cleartext there. LOCAL is the only env where we
    # accept http:// and therefore the only env that needs Secure=False.
    from ai_driven.config import Environment
    response.set_cookie(
        key="session_token",
        value=sess.token,
        httponly=True,
        samesite="lax",
        secure=settings.environment != Environment.LOCAL,
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
