"""Auth-related request models."""

from __future__ import annotations

from pydantic import BaseModel


class APIKeyCreate(BaseModel):
    team_name: str


class APIKeyResponse(BaseModel):
    api_key: str
    team_id: str
    team_name: str
