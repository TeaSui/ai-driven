"""Shared Pydantic base used across all API routers.

All HTTP API models use camelCase field names natively — both in
Python code and on the wire. This matches the JS/TS frontend convention
and removes the need for an alias-generator indirection layer.

SQLAlchemy ORM columns, Settings fields read from env vars, and local
Python variables retain snake_case per PEP 8; this base applies to
API boundary models only.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


def to_camel(name: str) -> str:
    """Deprecated — camelCase is now the native field name. Kept for
    backward-compat with transitional imports. Returns the name unchanged
    if it's already camelCase; converts snake_case defensively."""
    if "_" not in name:
        return name
    parts = name.split("_")
    return parts[0] + "".join(w.capitalize() for w in parts[1:])


class CamelModel(BaseModel):
    """Base for every Pydantic model on an HTTP request/response boundary.

    Fields are named in camelCase directly (sessionToken, teamId,
    createdAt), matching the wire format. No aliasing layer — the
    JSON you see is the Python attribute you access.
    """

    model_config = ConfigDict(extra="forbid")  # reject unknown fields
