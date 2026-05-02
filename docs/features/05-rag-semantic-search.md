# Feature 5: RAG — Semantic Code Search

## Purpose

Enable AI agents to semantically search large codebases using natural language queries. Combines vector similarity (Voyage AI embeddings) with keyword matching (PostgreSQL full-text search) for high-quality code retrieval.

## When It Activates

- `AI_DRIVEN_RAG_ENABLED=true` in config
- CodingAgent registers `semantic_search_code` tool alongside existing `search_code`
- Agent chooses between keyword search (GitHub API) and semantic search based on query complexity

## Architecture

```
Query: "authentication middleware"
        │
        ▼
┌─────────────────────────────────┐
│      CodeRetriever.search()     │
│                                 │
│  ┌───────────┐  ┌────────────┐  │
│  │  Vector    │  │  Keyword   │  │
│  │  Search    │  │  Search    │  │
│  │ (pgvector) │  │ (ts_rank)  │  │
│  └─────┬─────┘  └─────┬──────┘  │
│        │              │          │
│        ▼              ▼          │
│  ┌──────────────────────────┐   │
│  │  Reciprocal Rank Fusion  │   │
│  │  (RRF, k=60)             │   │
│  │  60% vector + 40% keyword│   │
│  └──────────────────────────┘   │
│              │                   │
│              ▼                   │
│     Top-K ranked results         │
└─────────────────────────────────┘
```

## Components

| File | Purpose |
|------|---------|
| `backend/src/ai_driven/rag/models.py` | `CodeEmbedding` SQLAlchemy model with `Vector(1024)` column |
| `backend/src/ai_driven/rag/chunker.py` | Python AST-based chunking (functions/classes) + fixed-size fallback |
| `backend/src/ai_driven/rag/embeddings.py` | `VoyageEmbedder` — async client for `voyage-code-3`, batch size 128 |
| `backend/src/ai_driven/rag/indexer.py` | `CodeIndexer` — walks repo, chunks files, embeds, upserts to pgvector |
| `backend/src/ai_driven/rag/retriever.py` | `CodeRetriever` — hybrid search with RRF merge |

## Database Schema

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE code_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id UUID NOT NULL REFERENCES repositories(id),
    file_path TEXT NOT NULL,
    chunk_content TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata_json JSONB DEFAULT '{}',
    last_commit_sha VARCHAR(40),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_emb_repo ON code_embeddings(repo_id);
CREATE INDEX idx_emb_vector ON code_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

## Chunking Strategy

1. **Python files**: AST-based — extracts functions and classes as individual chunks with line numbers
2. **All other files**: Fixed-size chunking (default 1000 chars) with overlap
3. **Skip**: files > 100KB, binary files, `node_modules/`, `.git/`, `__pycache__/`, etc.
4. **Indexable extensions**: `.py`, `.js`, `.ts`, `.tsx`, `.java`, `.go`, `.rs`, `.rb`, `.sql`, `.yaml`, etc.

## Tool Schema

```json
{
  "name": "semantic_search_code",
  "description": "Search the repository code semantically using natural language.",
  "input_schema": {
    "type": "object",
    "properties": {
      "query": {"type": "string", "description": "Natural language search query"},
      "repo_full_name": {"type": "string"},
      "top_k": {"type": "integer", "default": 10}
    },
    "required": ["query", "repo_full_name"]
  }
}
```

## Configuration

| Variable | Default | Notes |
|----------|---------|-------|
| `AI_DRIVEN_RAG_ENABLED` | `false` | Master switch |
| `AI_DRIVEN_RAG_CHUNK_SIZE` | `1000` | Max chars per chunk |
| `AI_DRIVEN_VOYAGE_API_KEY` | `""` | Required when enabled |

## Dependencies

- `pgvector>=0.3.0` — PostgreSQL vector extension bindings
- `voyageai>=0.3.0` — Voyage AI embedding client
