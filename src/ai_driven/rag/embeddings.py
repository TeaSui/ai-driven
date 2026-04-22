"""Voyage AI embeddings for code search."""

from __future__ import annotations

import logging

import httpx
from tenacity import retry, stop_after_attempt, wait_exponential

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

VOYAGE_API_URL = "https://api.voyageai.com/v1/embeddings"
VOYAGE_MODEL = "voyage-code-3"
EMBEDDING_DIM = 1024
MAX_BATCH_SIZE = 128


class VoyageEmbedder:
    """Voyage AI embeddings client for code."""

    def __init__(self, api_key: str | None = None) -> None:
        settings = get_settings()
        self._api_key = api_key or settings.voyage_api_key
        if not self._api_key:
            raise ValueError("VOYAGE_API_KEY not configured")
        self._client = httpx.AsyncClient(timeout=60.0)

    @retry(stop=stop_after_attempt(3), wait=wait_exponential(min=1, max=10))
    async def embed(self, texts: list[str], input_type: str = "document") -> list[list[float]]:
        """Embed a batch of texts using Voyage AI.

        Args:
            texts: List of text chunks to embed.
            input_type: "document" for indexing, "query" for search queries.

        Returns:
            List of embedding vectors (1024-dim each).
        """
        all_embeddings: list[list[float]] = []

        for i in range(0, len(texts), MAX_BATCH_SIZE):
            batch = texts[i:i + MAX_BATCH_SIZE]
            response = await self._client.post(
                VOYAGE_API_URL,
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": VOYAGE_MODEL,
                    "input": batch,
                    "input_type": input_type,
                },
            )
            response.raise_for_status()
            data = response.json()
            batch_embeddings = [item["embedding"] for item in data["data"]]
            all_embeddings.extend(batch_embeddings)

        return all_embeddings

    async def embed_query(self, query: str) -> list[float]:
        """Embed a single search query."""
        results = await self.embed([query], input_type="query")
        return results[0]

    async def close(self) -> None:
        await self._client.aclose()
