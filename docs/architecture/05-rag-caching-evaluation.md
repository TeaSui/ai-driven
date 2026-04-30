# RAG, Prompt Caching & Evaluation

Three cross-cutting subsystems that enhance agent quality and cost efficiency.

```mermaid
flowchart LR
    subgraph RAG["RAG Pipeline"]
        direction TB
        REPO["Git Repository\nCloned locally"]
        CHUNK["AST-Aware Chunker\nPython / TS / Java / Go / Kotlin"]
        EMBED["Voyage AI\nvoyage-code-3 (1024-dim)"]
        PGVEC[("pgvector\ncode_embeddings table")]

        REPO --> CHUNK
        CHUNK --> EMBED
        EMBED --> PGVEC
    end

    subgraph Query["Hybrid Retrieval"]
        direction TB
        Q["Search Query"]
        VEC["Vector Search\nCosine Distance\n(60% weight)"]
        KW["Keyword Search\nts_rank Full-Text\n(40% weight)"]
        RRF["RRF Merge\nReciprocal Rank Fusion"]
        RES["Top-K Code Chunks\nwith XML tags"]

        Q --> VEC
        Q --> KW
        VEC --> RRF
        KW --> RRF
        RRF --> RES
    end

    subgraph Cache["Prompt Caching"]
        direction TB
        SYS["System Prompt\ncache_control: ephemeral\n(if > 1024 tokens)"]
        TOOLS["Tool Schemas\ncache_control on last tool\n(if total > 1024 tokens)"]
        MSGS["Conversation Messages\ncache_control on FIRST user msg\n(stable prefix across iterations;\npinning the last msg caused\nper-turn cache misses)"]
        HIT["Cache HIT\n90% cost reduction\non cached input"]

        SYS --> HIT
        TOOLS --> HIT
        MSGS --> HIT
    end

    subgraph Eval["Evaluation System"]
        direction TB
        TASK["Completed Task\nInput + Output"]

        DIM_C["Coding Dimensions\nCorrectness (35%) / Completeness (25%)\nCode Quality (25%) / Security (15%)"]
        DIM_R["Review Dimensions\nIssue Detection (35%)\nFalse Positive Rate (25%)\nActionability (25%)\nTone (15%)"]
        DIM_D["DevOps Dimensions\nRoot Cause Accuracy (35%)\nCompleteness (25%)\nActionability (25%)\nUrgency Assessment (15%)"]

        GRADE["Sonnet Grader\nScore 0-10 per dimension\n+ Strengths + Weaknesses"]
        STORE[("task_evaluations\nPersisted scores")]

        TASK --> DIM_C
        TASK --> DIM_R
        TASK --> DIM_D
        DIM_C --> GRADE
        DIM_R --> GRADE
        DIM_D --> GRADE
        GRADE --> STORE
    end

    style RAG fill:#e8f5e9,stroke:#4caf50
    style Query fill:#e3f2fd,stroke:#2196f3
    style Cache fill:#f3e5f5,stroke:#9c27b0
    style Eval fill:#fff3e0,stroke:#ff9800
```

## RAG Details

### Auto-Enable Logic
- Triggered when repo has > 500 files (configurable via `rag_auto_enable_file_threshold`)
- Checks `code_embeddings` for existing index with matching `last_commit_sha`
- If not indexed: publishes `rag_indexing_start` event, indexes all chunks, publishes `rag_indexing_complete`
- Stores context in Redis: `repo_context:{full_name}` (shared with ReviewAgent)

### AST-Aware Chunkers

| Language | Strategy | Boundaries |
|----------|----------|------------|
| Python | AST parse | Top-level functions, classes, module preamble |
| TypeScript/JS | Regex | `function`, `class`, `interface`, `type`, `const ... = () =>` |
| Java | Regex | `public/private/protected (static)? class/void/...` |
| Go | Regex | `func`, `type ... struct`, `type ... interface` |
| Kotlin | Regex | `fun`, `class`, `object`, `interface` |
| Other | Fixed-size | 1000 chars, line-aligned |

### Hybrid Retrieval (RRF)
```
score(doc) = 0.6 / (k + vector_rank) + 0.4 / (k + keyword_rank)
```
- k = 60 (standard RRF constant)
- Vector: Voyage v3 cosine distance on pgvector
- Keyword: PostgreSQL `plainto_tsquery` + `ts_rank`

## Prompt Caching

### Cache Processing Order
`tools → system → messages` (Anthropic requirement)

### Cache Rules
- Minimum 1024 tokens per cacheable block
- Max 4 breakpoints per request
- 1-hour TTL
- Any byte change invalidates everything after the breakpoint
- Applied to both Anthropic and Bedrock providers

### Cost Impact
- Cache write: 25% surcharge on first request
- Cache read: 90% discount on subsequent requests
- Net savings on multi-turn conversations: ~70-80%

## Evaluation Dimensions

### Coding Agent
| Dimension | Weight | What it measures |
|-----------|--------|-----------------|
| Correctness | 35% | Does the output correctly address the requirements? Are there bugs or logic errors? |
| Completeness | 25% | Does the output cover all requirements? Are edge cases handled? |
| Code Quality | 25% | Is the code well-structured, readable, and following best practices? |
| Security | 15% | Are there security vulnerabilities? Are inputs validated properly? |

### Review Agent
| Dimension | Weight | What it measures |
|-----------|--------|-----------------|
| Issue Detection | 35% | Does the review correctly identify real bugs, logic errors, and code smells? |
| False Positive Rate | 25% | Are the flagged issues legitimate? Are there false alarms? |
| Actionability | 25% | Are review comments specific, actionable, with concrete fix suggestions? |
| Tone | 15% | Is the review constructive, professional, and balanced? |

### DevOps Agent
| Dimension | Weight | What it measures |
|-----------|--------|-----------------|
| Root Cause Accuracy | 35% | Is the root cause analysis correct and evidence-based? |
| Completeness | 25% | Does the analysis cover all relevant signals (metrics, logs, deployments)? |
| Actionability | 25% | Are the recommended actions specific, prioritized, and feasible? |
| Urgency Assessment | 15% | Is the severity/priority assessment appropriate for the situation? |
