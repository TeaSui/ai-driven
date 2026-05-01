"""Shared Pydantic base + helpers used across all API routers.

Every HTTP response body on this platform should serialize in camelCase
so the JS frontend can consume fields with their natural convention.
This was inconsistent before: /api/chat and /api/admin used CamelModel
but /api/auth/login emitted snake_case (session_token, team_id,
created_at), forcing frontend code to branch on the endpoint. Consolidated
here so every router imports the same base.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


def to_camel(name: str) -> str:
    """snake_case → camelCase. `session_token` → `sessionToken`."""
    parts = name.split("_")
    return parts[0] + "".join(w.capitalize() for w in parts[1:])


class CamelModel(BaseModel):
    """Base for all API response models.

    Serializes snake_case field names as camelCase in JSON (via alias
    generator) while still accepting snake_case on input (via
    populate_by_name=True). This makes endpoint definitions readable
    in Python and consumable from JavaScript without a field-by-field
    remap.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
