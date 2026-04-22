"""Code indexer — chunks and embeds repository code into pgvector."""

from __future__ import annotations

import logging
import os
import uuid
from pathlib import Path

from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.config import get_settings
from ai_driven.rag.chunker import CodeChunk, chunk_file
from ai_driven.rag.embeddings import VoyageEmbedder
from ai_driven.rag.models import CodeEmbedding

logger = logging.getLogger(__name__)

# File extensions to index
INDEXABLE_EXTENSIONS = {
    ".py", ".js", ".ts", ".tsx", ".jsx", ".java", ".kt", ".go",
    ".rs", ".rb", ".php", ".c", ".cpp", ".h", ".cs", ".swift",
    ".yaml", ".yml", ".json", ".toml", ".md", ".sql", ".sh",
    ".gradle", ".xml", ".html", ".css", ".scss",
}

# Directories to skip
SKIP_DIRS = {
    ".git", "node_modules", "__pycache__", ".venv", "venv",
    "dist", "build", ".next", ".cache", "target", ".gradle",
}

MAX_FILE_SIZE = 100_000  # 100KB — skip very large files


class CodeIndexer:
    """Indexes repository code into pgvector for semantic search."""

    def __init__(
        self,
        session: AsyncSession,
        embedder: VoyageEmbedder | None = None,
    ) -> None:
        self._session = session
        self._embedder = embedder or VoyageEmbedder()
        self._settings = get_settings()

    async def index_repo(
        self,
        repo_path: str,
        repo_id: uuid.UUID,
        commit_sha: str | None = None,
    ) -> int:
        """Index all code files in a repository directory.

        Args:
            repo_path: Local filesystem path to the cloned repo.
            repo_id: UUID of the repository in the database.
            commit_sha: Current HEAD commit SHA for tracking.

        Returns:
            Number of chunks indexed.
        """
        # Clear existing embeddings for this repo
        await self._session.execute(
            delete(CodeEmbedding).where(CodeEmbedding.repo_id == repo_id)
        )

        # Walk and chunk all indexable files
        all_chunks: list[CodeChunk] = []
        repo_root = Path(repo_path)

        for root, dirs, files in os.walk(repo_root):
            # Skip excluded directories
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

            for filename in files:
                filepath = Path(root) / filename
                if filepath.suffix not in INDEXABLE_EXTENSIONS:
                    continue
                if filepath.stat().st_size > MAX_FILE_SIZE:
                    continue

                try:
                    content = filepath.read_text(encoding="utf-8", errors="ignore")
                except Exception:
                    continue

                rel_path = str(filepath.relative_to(repo_root))
                chunks = chunk_file(rel_path, content, self._settings.rag_chunk_size)
                all_chunks.extend(chunks)

        if not all_chunks:
            logger.info("No indexable files found in %s", repo_path)
            return 0

        # Embed in batches
        texts = [c.content for c in all_chunks]
        logger.info("Embedding %d chunks from %s", len(texts), repo_path)
        embeddings = await self._embedder.embed(texts)

        # Upsert to database
        for chunk, embedding in zip(all_chunks, embeddings):
            record = CodeEmbedding(
                repo_id=repo_id,
                file_path=chunk.file_path,
                chunk_content=chunk.content,
                chunk_index=chunk.chunk_index,
                embedding=embedding,
                metadata_json={
                    "start_line": chunk.start_line,
                    "end_line": chunk.end_line,
                    "chunk_type": chunk.chunk_type,
                },
                last_commit_sha=commit_sha,
            )
            self._session.add(record)

        await self._session.flush()
        logger.info("Indexed %d chunks for repo %s", len(all_chunks), repo_id)
        return len(all_chunks)
