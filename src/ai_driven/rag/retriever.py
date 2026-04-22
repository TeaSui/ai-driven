"""Hybrid code retriever — pgvector cosine + BM25 keyword with RRF merge."""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.rag.embeddings import VoyageEmbedder
from ai_driven.rag.models import CodeEmbedding

logger = logging.getLogger(__name__)


@dataclass
class RetrievalResult:
    file_path: str
    content: str
    score: float
    chunk_index: int
    metadata: dict[str, Any]


class CodeRetriever:
    """Hybrid retriever combining vector similarity and keyword search."""

    def __init__(
        self,
        session: AsyncSession,
        embedder: VoyageEmbedder | None = None,
    ) -> None:
        self._session = session
        self._embedder = embedder or VoyageEmbedder()

    async def search(
        self,
        query: str,
        repo_id: uuid.UUID,
        top_k: int = 10,
        vector_weight: float = 0.6,
    ) -> list[RetrievalResult]:
        """Hybrid search combining vector similarity and keyword matching.

        Args:
            query: Natural language search query.
            repo_id: Repository to search within.
            top_k: Number of results to return.
            vector_weight: Weight for vector results in RRF (keyword weight = 1 - vector_weight).

        Returns:
            Ranked list of code chunks.
        """
        # Get vector results
        vector_results = await self._vector_search(query, repo_id, top_k * 2)

        # Get keyword results
        keyword_results = await self._keyword_search(query, repo_id, top_k * 2)

        # Reciprocal Rank Fusion
        merged = self._rrf_merge(vector_results, keyword_results, vector_weight, top_k)
        return merged

    async def _vector_search(
        self, query: str, repo_id: uuid.UUID, limit: int
    ) -> list[RetrievalResult]:
        """Search using pgvector cosine similarity."""
        query_embedding = await self._embedder.embed_query(query)

        # Use pgvector's cosine distance operator
        stmt = (
            select(
                CodeEmbedding.file_path,
                CodeEmbedding.chunk_content,
                CodeEmbedding.chunk_index,
                CodeEmbedding.metadata_json,
                CodeEmbedding.embedding.cosine_distance(query_embedding).label("distance"),
            )
            .where(CodeEmbedding.repo_id == repo_id)
            .order_by("distance")
            .limit(limit)
        )

        result = await self._session.execute(stmt)
        rows = result.all()

        return [
            RetrievalResult(
                file_path=row.file_path,
                content=row.chunk_content,
                score=1.0 - row.distance,  # Convert distance to similarity
                chunk_index=row.chunk_index,
                metadata=row.metadata_json or {},
            )
            for row in rows
        ]

    async def _keyword_search(
        self, query: str, repo_id: uuid.UUID, limit: int
    ) -> list[RetrievalResult]:
        """Search using PostgreSQL full-text search (ts_rank)."""
        # Use plainto_tsquery for simple keyword matching
        stmt = text("""
            SELECT file_path, chunk_content, chunk_index, metadata_json,
                   ts_rank(to_tsvector('english', chunk_content), plainto_tsquery('english', :query)) AS rank
            FROM code_embeddings
            WHERE repo_id = :repo_id
              AND to_tsvector('english', chunk_content) @@ plainto_tsquery('english', :query)
            ORDER BY rank DESC
            LIMIT :limit
        """)

        result = await self._session.execute(
            stmt, {"query": query, "repo_id": str(repo_id), "limit": limit}
        )
        rows = result.all()

        return [
            RetrievalResult(
                file_path=row.file_path,
                content=row.chunk_content,
                score=float(row.rank),
                chunk_index=row.chunk_index,
                metadata=row.metadata_json or {},
            )
            for row in rows
        ]

    @staticmethod
    def _rrf_merge(
        vector_results: list[RetrievalResult],
        keyword_results: list[RetrievalResult],
        vector_weight: float,
        top_k: int,
        rrf_k: int = 60,
    ) -> list[RetrievalResult]:
        """Merge results using Reciprocal Rank Fusion.

        RRF score = weight * 1/(k + rank) for each list.
        """
        scores: dict[str, float] = {}
        result_map: dict[str, RetrievalResult] = {}
        keyword_weight = 1.0 - vector_weight

        for rank, r in enumerate(vector_results):
            key = f"{r.file_path}:{r.chunk_index}"
            scores[key] = scores.get(key, 0) + vector_weight / (rrf_k + rank + 1)
            result_map[key] = r

        for rank, r in enumerate(keyword_results):
            key = f"{r.file_path}:{r.chunk_index}"
            scores[key] = scores.get(key, 0) + keyword_weight / (rrf_k + rank + 1)
            if key not in result_map:
                result_map[key] = r

        # Sort by RRF score descending
        sorted_keys = sorted(scores.keys(), key=lambda k: scores[k], reverse=True)[:top_k]

        return [
            RetrievalResult(
                file_path=result_map[k].file_path,
                content=result_map[k].content,
                score=scores[k],
                chunk_index=result_map[k].chunk_index,
                metadata=result_map[k].metadata,
            )
            for k in sorted_keys
        ]
