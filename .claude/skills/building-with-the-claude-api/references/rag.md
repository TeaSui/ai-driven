# RAG

Retrieval-Augmented Generation: chunking, embeddings, BM25 lexical search, and multi-index hybrid retrieval.

> See also: [additional-notes.md § S4](additional-notes.md#s4--ragmd--depth-additions) — why cosine similarity works (embedding normalization); when to reach for semantic chunking; BM25 4-step walkthrough; worked RRF example; Voyage setup.

## Contents

- [When to use RAG](#when-to-use-rag)
- [The RAG flow](#the-rag-flow)
- [Chunking strategies](#chunking-strategies)
- [Embeddings and semantic search](#embeddings-and-semantic-search)
- [BM25 lexical search](#bm25-lexical-search)
- [Multi-index hybrid retrieval (RRF)](#multi-index-hybrid-retrieval-rrf)
- [Injecting chunks into the prompt](#injecting-chunks-into-the-prompt)
- [Gotchas](#gotchas)
- [Sources](#sources)

## When to use RAG

Use RAG when your knowledge source is:
- Larger than fits in a single prompt.
- A collection of documents where only a subset is relevant per query.
- Cost-sensitive (small prompts cost less and run faster).

Don't use RAG when:
- The whole corpus fits comfortably in context *and* cache discounts cover the repeated input.
- A single fact is needed on a predictable path — a tool call is simpler.

## The RAG flow

1. **Preprocess once**: chunk documents, embed chunks (and/or build a BM25 index), store.
2. **At query time**: retrieve top-k chunks for the user's question.
3. **Inject** the chunks into the prompt alongside the question.
4. Call Claude; it answers from the retrieved context.

## Chunking strategies

Four strategies with different tradeoffs. Pick based on document structure.

### Fixed-size character chunking

Predictable chunk sizes, zero structure awareness.

```python
def chunk_by_char(text, chunk_size=1000, overlap=100):
    chunks = []
    start = 0
    while start < len(text):
        chunks.append(text[start : start + chunk_size])
        start += chunk_size - overlap
    return chunks
```

Overlap prevents losing context at chunk boundaries. Good default: 10–20% overlap.

### Section-based chunking

Splits on document structure (markdown headers). Keeps semantic units intact.

```python
import re

def chunk_by_section(text):
    return re.split(r"\n## ", text)
```

Prefer for docs with clean markdown headings.

### Sentence chunking

Groups N sentences per chunk. Good for narrative prose.

```python
def chunk_by_sentence(text, sentences_per_chunk=5):
    sentences = re.split(r"(?<=[.!?])\s+", text)
    return [
        " ".join(sentences[i : i + sentences_per_chunk])
        for i in range(0, len(sentences), sentences_per_chunk)
    ]
```

### Semantic chunking

Embeds sliding windows; splits at points where consecutive embeddings diverge. Highest quality, slowest + most expensive. Use after a fixed baseline if relevance needs a boost.

## Embeddings and semantic search

Embeddings map text to vectors so similar meanings land close in vector space. Claude doesn't ship embeddings; use Voyage AI.

```python
import voyageai
import numpy as np

vo = voyageai.Client()

def embed(texts, input_type="document"):
    # input_type: "document" for chunks, "query" for user questions
    result = vo.embed(texts, model="voyage-3-large", input_type=input_type)
    return result.embeddings
```

Cosine similarity measures closeness (−1 to 1). Cosine distance = 1 − similarity.

```python
def cosine_similarity(a, b):
    a, b = np.array(a), np.array(b)
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

class VectorIndex:
    def __init__(self):
        self.chunks = []
        self.embeddings = []

    def add_document(self, chunk):
        self.chunks.append(chunk)
        self.embeddings.append(embed([chunk["content"]], input_type="document")[0])

    def search(self, query, k=5):
        q_emb = embed([query], input_type="query")[0]
        scored = [
            (cosine_similarity(q_emb, e), chunk)
            for e, chunk in zip(self.embeddings, self.chunks)
        ]
        scored.sort(key=lambda x: x[0], reverse=True)
        return [c for _, c in scored[:k]]
```

Semantic search catches paraphrases ("wound up" ≈ "was upset") but can miss exact-keyword queries (product codes, error codes, rare names).

## BM25 lexical search

BM25 ranks by term-frequency overlap with the query. Excellent for exact keywords, proper nouns, codes — cases where embeddings miss.

```python
# pip install bm25s or rank_bm25
from bm25s import BM25

class BM25Index:
    def __init__(self):
        self.chunks = []
        self.bm25 = None

    def add_document(self, chunk):
        self.chunks.append(chunk)
        corpus = [c["content"] for c in self.chunks]
        self.bm25 = BM25().index(corpus)

    def search(self, query, k=5):
        results, _ = self.bm25.retrieve([query], k=k)
        return [self.chunks[i] for i in results[0]]
```

BM25 is fast, cheap, and explainable. Its weakness is pure synonym handling — "car" won't match "automobile."

## Multi-index hybrid retrieval (RRF)

Combine semantic + lexical results with **Reciprocal Rank Fusion**. RRF sums `1 / (k + rank)` across indexes so a chunk that ranks well in *either* index surfaces.

Formula:
```
RRF_score(d) = Σ_over_indexes   1 / (k + rank_i(d))
```
`k` is a smoothing constant; `60` is the standard default.

```python
class Retriever:
    def __init__(self, *indexes, rrf_k=60):
        self.indexes = indexes
        self.rrf_k = rrf_k

    def search(self, query, k=5):
        # Gather per-index rankings
        ranked_lists = [idx.search(query, k=k * 3) for idx in self.indexes]

        scores = {}
        for ranks in ranked_lists:
            for rank, chunk in enumerate(ranks):
                key = chunk["content"]  # or an id
                scores[key] = scores.get(key, 0) + 1 / (self.rrf_k + rank + 1)

        unique_chunks = {c["content"]: c for ranks in ranked_lists for c in ranks}
        fused = sorted(unique_chunks.values(), key=lambda c: -scores[c["content"]])
        return fused[:k]

# Usage
vec_index = VectorIndex()
bm25_index = BM25Index()
for chunk in chunks:
    vec_index.add_document(chunk)
    bm25_index.add_document(chunk)

retriever = Retriever(vec_index, bm25_index)
top_chunks = retriever.search("How does the cache TTL work?", k=5)
```

Hybrid retrieval is the default modern RAG approach. It covers both paraphrase and keyword queries without tuning per-query strategy.

## Injecting chunks into the prompt

Wrap retrieved chunks in XML tags so Claude knows what's context vs. instruction.

```python
def build_rag_prompt(question, chunks):
    context = "\n\n".join(
        f"<chunk source=\"{c.get('source', '')}\">\n{c['content']}\n</chunk>"
        for c in chunks
    )
    return f"""Answer the user's question using only the context below.
If the context doesn't contain the answer, say "I don't know."

<context>
{context}
</context>

<question>{question}</question>"""

prompt = build_rag_prompt(user_question, retriever.search(user_question, k=5))
response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{"role": "user", "content": prompt}],
)
```

For citation-grade answers, see [advanced-features.md#citations](advanced-features.md) — use `document` blocks with `citations: {"enabled": True}` instead of inlining chunks as text.

## Gotchas

- **Chunk size matters more than you think.** 500–1000 characters is a good default for mixed content; 200–400 for Q&A lookups; 2000+ for long-form analysis.
- **Embed queries with `input_type="query"`, not `"document"`.** Voyage models are asymmetric; using the wrong type costs ~5–10% relevance.
- **BM25 tokenization is sensitive to stopwords and case.** For code corpora, disable stopword filtering; for prose, keep it on.
- **Retrieval ≠ ranking.** Top-k by similarity isn't always top-k by usefulness. Add a rerank step (e.g., `voyage-rerank-2`) for the final top-5.
- **RRF `k=60` is convention, not tuning.** Lower `k` puts more weight on top ranks; higher `k` smooths. 60 is fine for most setups.
- **Retrieval failures look like Claude hallucinations.** If answers are wrong but Claude sounds confident, instrument retrieval first — print the top-k chunks and check them before blaming the prompt.
- **Index state isn't free.** Rebuilding BM25 on every `add_document` call is O(n²) total; batch-build once if you have >1000 chunks.
- **Overlap pays off.** Questions near chunk boundaries are the #1 retrieval failure. 10–20% overlap fixes most cases.

## Sources

Distilled from the prior 62-lesson curriculum (lessons 32–37 plus 35a). The source lessons have been retired; this reference is self-contained. Embedding model details live alongside `../docs/model-capabilities/embeddings.md`. See [additional-notes.md § S4](additional-notes.md#s4--ragmd--depth-additions) for depth addenda (normalization, BM25 walkthrough, RRF worked example, Voyage setup).
