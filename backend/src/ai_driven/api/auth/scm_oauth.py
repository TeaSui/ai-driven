"""SCM OAuth endpoints — GitHub + Bitbucket authorize/callback (PR 1).

Two endpoints per provider under ``/api/auth/scm``:

* ``GET /{provider}/authorize`` — ``require_admin``. Creates state, 302
  to provider's consent screen. Query param ``scope`` is validated
  against :data:`ai_driven.scm.oauth_helpers.ALLOWED_SCOPES`.
* ``GET /{provider}/callback`` — PUBLIC (listed in PUBLIC_PATHS).
  Atomic ``GETDEL`` on the state; provider-specific token exchange +
  ``/user`` lookup; upsert a ``user_scm_connections`` row. On
  ``uq_team_provider_scmuser`` collision returns 409
  ``SCM_IDENTITY_CLAIMED``. Finally 302 to the settings page.

All error responses use the canonical envelope from ``api/_shared.py``.
"""

from __future__ import annotations

import logging
import urllib.parse
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.auth.oauth_state import consume_state, create_state
from ai_driven.auth.scm_tokens import encrypt_token
from ai_driven.config import get_settings
from ai_driven.db.postgres.models import UserScmConnection
from ai_driven.db.postgres.session import get_session
from ai_driven.scm.oauth_helpers import ALLOWED_SCOPES

logger = logging.getLogger(__name__)

router = APIRouter()


# ---------------------------------------------------------------------------
# Provider endpoints — kept as plain constants (not a class) so individual
# URLs are grep-able when debugging a 502 from the provider.
# ---------------------------------------------------------------------------

_GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
_GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token"
_GITHUB_USER_URL = "https://api.github.com/user"

_BITBUCKET_AUTHORIZE_URL = "https://bitbucket.org/site/oauth2/authorize"
_BITBUCKET_TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token"
_BITBUCKET_USER_URL = "https://api.bitbucket.org/2.0/user"

# Bitbucket scopes do not overlap with GitHub's. Map GitHub-style scope
# names to Bitbucket equivalents on the authorize side so the UI can
# speak one vocabulary.
_BITBUCKET_SCOPE_MAP = {
    "public_repo": "account pullrequest",
    "repo": "account pullrequest:write",
}

# Final landing URL after a successful callback. Query param ``connected``
# tells the settings page which provider card to flash green.
_CONNECTED_REDIRECT = "/dashboard/settings/connections?connected={provider}"
_FAILED_REDIRECT = "/dashboard/settings/connections?error={reason}"


def _redirect_error(code: str, extra: dict[str, str] | None = None) -> RedirectResponse:
    """302 back to the settings page with ?error=CODE (+ optional params).

    Browser-facing errors during the callback (state invalid, token
    exchange failed, identity already claimed) MUST redirect to the UI
    so the user sees a toast rather than a raw JSON error page.
    """
    from urllib.parse import urlencode
    params: dict[str, str] = {"error": code}
    if extra:
        params.update(extra)
    return RedirectResponse(
        url=f"/dashboard/settings/connections?{urlencode(params)}",
        status_code=302,
    )


def _error(status: int, code: str, message: str, details: list[dict] | None = None) -> JSONResponse:
    """Canonical error envelope — matches api/_shared.py convention.

    Use for non-callback routes (authorize validation, /me/connections
    endpoints). Callback paths should use ``_redirect_error`` so errors
    land in the UI toast instead of a broken JSON tab.
    """
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


def _validate_provider(provider: str) -> str | None:
    """Return error-code string if provider is unknown, else None."""
    if provider not in {"github", "bitbucket"}:
        return "PROVIDER_NOT_SUPPORTED"
    return None


def _resolve_redirect_uri(provider: str) -> str:
    """Build the full callback URL to register with the provider.

    Must match exactly the URL registered on the OAuth App — GitHub and
    Bitbucket both reject token exchanges where the callback URL
    differs between ``/authorize`` and ``/access_token``.
    """
    base = get_settings().scm_oauth_redirect_base.rstrip("/")
    return f"{base}/api/auth/scm/{provider}/callback"


# ---------------------------------------------------------------------------
# Authorize
# ---------------------------------------------------------------------------


@router.get("/{provider}/authorize")
async def authorize(
    provider: str,
    request: Request,
    scope: str = "public_repo",
    member: TeamMemberInfo = Depends(require_admin),
) -> Any:
    """Kick off the OAuth flow — validates provider + scope, creates
    state, redirects to the provider's consent screen.

    ``scope`` defaults to ``public_repo`` per the brief's least-privilege
    rollout. The UI escalates to ``?scope=repo`` only after a 412
    ``SCM_SCOPE_UPGRADE_REQUIRED`` from a private-repo action (PR 3).
    """
    err = _validate_provider(provider)
    if err:
        return _error(400, err, f"Unknown SCM provider: {provider}")

    if scope not in ALLOWED_SCOPES:
        return _error(
            400,
            "SCOPE_NOT_ALLOWED",
            f"Scope '{scope}' is not permitted.",
            [{"allowedScopes": sorted(ALLOWED_SCOPES)}],
        )

    settings = get_settings()
    client_id = (
        settings.github_oauth_client_id
        if provider == "github"
        else settings.bitbucket_oauth_client_id
    )
    if not client_id:
        return _error(
            500,
            "OAUTH_NOT_CONFIGURED",
            f"{provider} OAuth App is not configured on this environment.",
        )

    state = await create_state(
        member_id=member.member_id,
        team_id=member.team_id,
        provider=provider,
        requested_scope=scope,
    )
    redirect_uri = _resolve_redirect_uri(provider)

    if provider == "github":
        # Always include read:user so /user works regardless of
        # public_repo vs repo choice.
        scope_param = f"{scope} read:user"
        params = {
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "scope": scope_param,
            "state": state,
        }
        url = f"{_GITHUB_AUTHORIZE_URL}?{urllib.parse.urlencode(params)}"
    else:  # bitbucket
        bb_scope = _BITBUCKET_SCOPE_MAP.get(scope, "account")
        params = {
            "client_id": client_id,
            "response_type": "code",
            "scope": bb_scope,
            "state": state,
            "redirect_uri": redirect_uri,
        }
        url = f"{_BITBUCKET_AUTHORIZE_URL}?{urllib.parse.urlencode(params)}"

    return RedirectResponse(url=url, status_code=302)


# ---------------------------------------------------------------------------
# Callback
# ---------------------------------------------------------------------------


async def _github_exchange_code(code: str, redirect_uri: str) -> dict[str, Any]:
    """POST code to GitHub, return the parsed JSON payload.

    Raises :class:`httpx.HTTPError` on network / non-2xx errors so the
    caller can map to a 502.
    """
    settings = get_settings()
    payload = {
        "client_id": settings.github_oauth_client_id,
        "client_secret": settings.github_oauth_client_secret.get_secret_value(),
        "code": code,
        "redirect_uri": redirect_uri,
    }
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            _GITHUB_TOKEN_URL,
            data=payload,
            headers={"Accept": "application/json"},
        )
        resp.raise_for_status()
        return resp.json()


async def _github_fetch_user(access_token: str) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.get(
            _GITHUB_USER_URL,
            headers={
                "Authorization": f"token {access_token}",
                "Accept": "application/vnd.github+json",
            },
        )
        resp.raise_for_status()
        return resp.json()


async def _bitbucket_exchange_code(code: str, redirect_uri: str) -> dict[str, Any]:
    settings = get_settings()
    auth = (
        settings.bitbucket_oauth_client_id,
        settings.bitbucket_oauth_client_secret.get_secret_value(),
    )
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            _BITBUCKET_TOKEN_URL,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
            },
            auth=auth,
        )
        resp.raise_for_status()
        return resp.json()


async def _bitbucket_fetch_user(access_token: str) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.get(
            _BITBUCKET_USER_URL,
            headers={"Authorization": f"Bearer {access_token}"},
        )
        resp.raise_for_status()
        return resp.json()


def _extract_user_fields(provider: str, user_json: dict[str, Any]) -> tuple[str, str, str | None]:
    """Return ``(scm_user_id, scm_username, scm_email)`` for the upsert."""
    if provider == "github":
        return (
            str(user_json.get("id", "")),
            str(user_json.get("login", "")),
            user_json.get("email"),
        )
    # bitbucket
    return (
        str(user_json.get("uuid") or user_json.get("account_id") or ""),
        str(user_json.get("username") or user_json.get("nickname") or ""),
        None,  # Bitbucket /user does not return email
    )


@router.get("/{provider}/callback")
async def callback(
    provider: str,
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> Any:
    """OAuth redirect target. Public path — state is the only auth.

    Steps:
      1. Validate provider.
      2. Atomic GETDEL state → 400 on miss.
      3. Exchange code for access_token → 502 on provider error.
      4. Fetch /user → 502 on provider error.
      5. Encrypt token; upsert connection row; 409 on identity collision.
      6. 302 to the settings page.
    """
    err = _validate_provider(provider)
    if err:
        return _redirect_error(err)

    code = request.query_params.get("code") or ""
    state = request.query_params.get("state") or ""
    if not code or not state:
        return _redirect_error("OAUTH_INVALID_REQUEST")

    payload = await consume_state(state)
    if payload is None:
        return _redirect_error("OAUTH_INVALID_STATE")

    # Defensive: state carries the provider we asked for; mismatched
    # provider in the redirect path means either tampering or a user
    # clicking an old link. Treat as invalid state.
    if payload.get("provider") != provider:
        return _redirect_error("OAUTH_INVALID_STATE")

    member_id = payload.get("memberId")
    team_id = payload.get("teamId")
    if not member_id or not team_id:
        return _redirect_error("OAUTH_INVALID_STATE")

    redirect_uri = _resolve_redirect_uri(provider)
    try:
        if provider == "github":
            token_json = await _github_exchange_code(code, redirect_uri)
        else:
            token_json = await _bitbucket_exchange_code(code, redirect_uri)
    except httpx.HTTPError as ex:
        body = ""
        if isinstance(ex, httpx.HTTPStatusError):
            body = ex.response.text[:500]
        logger.warning(
            "oauth_token_exchange_failed provider=%s err=%s body=%s",
            provider,
            ex,
            body,
        )
        return _redirect_error("OAUTH_TOKEN_EXCHANGE_FAILED")

    access_token = token_json.get("access_token")
    if not access_token:
        logger.warning("oauth_token_missing provider=%s payload_keys=%s", provider, list(token_json.keys()))
        return _redirect_error("OAUTH_TOKEN_EXCHANGE_FAILED")
    refresh_token = token_json.get("refresh_token")
    granted_scope = str(token_json.get("scope") or payload.get("requestedScope") or "")
    expires_in = token_json.get("expires_in")
    expires_at: datetime | None = None
    if isinstance(expires_in, (int, float)) and expires_in > 0:
        expires_at = datetime.now(timezone.utc) + timedelta(seconds=int(expires_in))

    try:
        if provider == "github":
            user_json = await _github_fetch_user(access_token)
        else:
            user_json = await _bitbucket_fetch_user(access_token)
    except httpx.HTTPError as ex:
        logger.warning("oauth_user_fetch_failed provider=%s err=%s", provider, ex)
        return _redirect_error("OAUTH_USER_FETCH_FAILED")

    scm_user_id, scm_username, scm_email = _extract_user_fields(provider, user_json)
    if not scm_user_id or not scm_username:
        return _redirect_error("OAUTH_USER_FETCH_FAILED")

    # --- Encrypt + upsert ---
    try:
        access_enc = encrypt_token(access_token)
        refresh_enc = encrypt_token(refresh_token) if refresh_token else None
    except RuntimeError as ex:
        logger.error("scm_token_key_unavailable: %s", ex)
        return _redirect_error("OAUTH_NOT_CONFIGURED")

    import uuid as _uuid

    team_uuid = _uuid.UUID(team_id)
    member_uuid = _uuid.UUID(member_id)

    # Existing row (member, provider)? Update in place. Otherwise insert.
    # We do a SELECT+UPDATE/INSERT rather than ON CONFLICT so we can
    # distinguish the (member, provider) collision (treat as update)
    # from the (team, provider, scm_user_id) collision (409) cleanly.
    existing = await session.execute(
        select(UserScmConnection).where(
            UserScmConnection.team_member_id == member_uuid,
            UserScmConnection.provider == provider,
        )
    )
    row = existing.scalar_one_or_none()

    now = datetime.now(timezone.utc)
    try:
        if row is None:
            new_row = UserScmConnection(
                team_id=team_uuid,
                team_member_id=member_uuid,
                provider=provider,
                scm_user_id=scm_user_id,
                scm_username=scm_username,
                scm_email=scm_email,
                access_token_enc=access_enc,
                refresh_token_enc=refresh_enc,
                scope=granted_scope,
                expires_at=expires_at,
                connected_at=now,
            )
            session.add(new_row)
            await session.commit()
        else:
            row.scm_user_id = scm_user_id
            row.scm_username = scm_username
            row.scm_email = scm_email
            row.access_token_enc = access_enc
            row.refresh_token_enc = refresh_enc
            row.scope = granted_scope
            row.expires_at = expires_at
            row.connected_at = now
            await session.commit()
    except IntegrityError as ex:
        await session.rollback()
        # Second unique constraint — another member of the same team is
        # already connected as this SCM identity. 302 back to the UI so
        # the toast can render with scmUsername interpolated.
        if "uq_team_provider_scmuser" in str(ex.orig) or "uq_team_provider_scmuser" in str(ex):
            return _redirect_error(
                "SCM_IDENTITY_CLAIMED",
                extra={"scmUsername": scm_username},
            )
        logger.warning("oauth_callback_integrity_error: %s", ex)
        return _redirect_error("INTERNAL_ERROR")

    return RedirectResponse(
        url=_CONNECTED_REDIRECT.format(provider=provider),
        status_code=302,
    )
