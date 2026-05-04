# Additional notes (from prior curriculum)

Supplementary material ported from the earlier 62-lesson curriculum. Each section names the skill reference it augments. Load this file only when the cross-ref points here.

---

## S2 · `basics.md` — Temperature intuition

`temperature` directly reshapes the token-sampling distribution, it doesn't just "make things creative".

- **At `temperature=0.0`** — the highest-probability token gets effectively 100%. Sampling is near-deterministic (though **not bit-identical** across calls — rare ties resolve arbitrarily).
- **At `temperature=1.0`** — probability mass spreads more evenly across plausible tokens. More variety per run, but also more off-target outputs.

Worked example — continuation for `"What do you think"`:

| Token | Raw prob | At T=0.0 | At T=1.0 |
|---|---|---|---|
| `about` | 30% | ~100% | ~30% |
| `would` | 20% | ~0% | ~20% |
| `of` | 10% | ~0% | ~10% |
| … | … | … | distributed |

**Practical implication:** `temperature=0` does not guarantee the same output twice, and `temperature=1` does not guarantee variation (short prompts often still yield similar outputs because the distribution is already peaked). Temperature only shifts odds; the seed and input determine actual draws.

On thinking-enabled models, temperature has reduced effect inside the thinking block (reasoning is less sensitive).

---

## S3 · `evaluation.md` — Depth additions

### Why naive testing fails

"Run the prompt once, eyeball the output, ship it" is the most common eval mistake. Production prompts see input distributions no developer manually tests: malformed queries, adversarial phrasing, edge lengths, code-switching between languages. A 1-2-try sanity check catches the happy path and misses everything that actually breaks in production. Evaluation is the *measurement* layer — distinct from prompt engineering, which is the *writing* layer — and exists because human spot-checks don't scale to input diversity.

### Rubric-design checklist (for model graders)

Before writing the grader prompt, enumerate criteria that you can name and define. For a code-generation prompt, a usable rubric splits into:

| Criterion | What it measures | Best grader |
|---|---|---|
| **Format** | Output is *only* the requested code type (Python / JSON / Regex), no prose | Code grader (regex / parser) |
| **Valid syntax** | Output parses in the target language | Code grader (`ast.parse`, `json.loads`, `re.compile`) |
| **Task following** | Output actually does what the user asked | Model grader |
| **Completeness** | All requested subtasks present | Model grader |
| **Safety** | No sensitive data, no harmful payloads | Model grader (separate call) |

Code graders are cheap and deterministic — use them for anything a parser can answer. Reserve model graders for subjective criteria.

### Output-format instrumentation via prefill

When grading code outputs, you can nudge Claude to emit just the raw snippet by prefilling an assistant message with a generic code fence:

```python
add_assistant_message(messages, "```code")
eval_output = chat(messages, stop_sequences=["```"])
```

This works on models where prefill is still supported (Sonnet 4.5, Haiku 4.5). For Opus 4.7 / 4.6 / Sonnet 4.6, use structured outputs (`output_config.format` with a JSON schema) instead — see `model-capabilities.md`. The net effect is the same: Claude's output is already shaped for the parser, so the code grader doesn't have to strip explanatory text.

### Result-shape narrative

Every eval run produces a list of result dicts; the useful shape is:

```python
{
    "output": "<model's raw response>",
    "test_case": {...},           # original dataset row
    "score": 7.5,                 # combined or single-grader score
    "reasoning": "..."            # model-grader justification, if any
}
```

Keep `reasoning` alongside `score` even when you only average the numbers — it is what lets you triage why the score moved when you change a prompt. `statistics.mean(r["score"] for r in results)` gives the top-line metric; reasoning strings give the diagnostic.

Code-grader + model-grader combination is usually averaged: `score = (model_score + syntax_score) / 2`. Weights can shift — double the syntax weight if malformed output is unacceptable, or drop it to 0 for prose-only prompts.

---

## S4 · `rag.md` — Depth additions

### Why cosine similarity works: embedding normalization

Voyage and most modern embedding APIs normalize each vector to unit length (magnitude = 1.0) before returning it. That's why you can use cosine similarity — once vectors live on the unit circle, cosine of the angle between them is just their dot product.

Visualized: `[0.944, 0.331]` and `[0.295, 0.955]` are both points on the unit circle. Their cosine similarity (the dot product `0.944·0.295 + 0.331·0.955 ≈ 0.595`) measures the angular distance, not the absolute distance. Normalization is what decouples "relatedness" from vector length.

Cosine similarity ranges from `-1` (opposite) through `0` (perpendicular / unrelated) to `1` (identical direction). Vector databases sometimes surface **cosine distance** instead: `distance = 1 - similarity`. Lower is better for distance; higher is better for similarity. Always check which your index returns.

### Semantic-based chunking — when to reach for it

Three common chunking strategies, in ascending order of cost and quality:

1. **Size-based** — fixed character count with overlap. Fastest, works on anything, cuts words mid-sentence. Default fallback.
2. **Structure-based** — split on document structure (Markdown headers, paragraph breaks). Cleanest output, requires structural guarantees.
3. **Sentence-based** — split on sentence boundaries, group N sentences per chunk with overlap. Middle ground.
4. **Semantic-based** — split into sentences, embed each, group consecutive sentences by embedding similarity. Highest quality, computationally expensive (embeds every sentence before chunking).

Reach for semantic chunking only when retrieval quality is demonstrably bottlenecked by chunk boundaries — otherwise the added cost doesn't pay back.

### BM25 — 4-step walkthrough

BM25 (Best Match 25) is the standard lexical-search ranker. Given a query and a corpus of documents:

1. **Tokenize** — split the query into terms. `"find INC-2023-Q4-011"` → `["find", "INC-2023-Q4-011"]`.
2. **Count term frequency** — for each term, count occurrences across all documents. `"find"` might appear 200 times; `"INC-2023-Q4-011"` twice.
3. **Weight by inverse frequency** — rare terms get high weight, common terms get low weight. `"find"` is near-useless for ranking; `"INC-2023-Q4-011"` is extremely discriminative.
4. **Score and rank** — for each document, sum `weight × term-occurrence-in-doc` across query terms. Documents containing the rare terms score highest.

BM25 is the correct choice for any query where exact strings matter (incident IDs, error codes, product SKUs, quoted phrases). Semantic search alone misses these because embeddings smooth over exact tokens.

### Reciprocal Rank Fusion — worked example

When combining a vector index and a BM25 index, you can't just add their scores (different scales). RRF normalizes across rankers by scoring each document by its **rank** in each ranker, not its raw score:

```
RRF_score(d) = Σ_i  1 / (k + rank_i(d))
```

`k` is a smoothing constant — `60` is the literature default; using `1` makes the arithmetic more readable. `rank_i(d)` is `d`'s position (1-indexed) in ranker `i`'s result list.

Example — query returns:
- VectorIndex: Section 2 (rank 1), Section 7 (rank 2), Section 6 (rank 3)
- BM25Index: Section 6 (rank 1), Section 2 (rank 2), Section 7 (rank 3)

With `k=1`:

| Doc | Vector rank | BM25 rank | RRF score |
|---|---|---|---|
| Section 2 | 1 | 2 | `1/(1+1) + 1/(1+2) = 0.833` |
| Section 6 | 3 | 1 | `1/(1+3) + 1/(1+1) = 0.750` |
| Section 7 | 2 | 3 | `1/(1+2) + 1/(1+3) = 0.583` |

Final hybrid ranking: **Section 2 → Section 6 → Section 7.** Section 2 wins because it placed well in both rankers. RRF naturally rewards consensus without needing per-ranker score calibration.

### Voyage setup

Install and configure once:

```bash
pip install voyageai python-dotenv
```

```python
# .env
VOYAGE_API_KEY=pa-...
```

```python
from dotenv import load_dotenv
import voyageai

load_dotenv()
voyage = voyageai.Client()  # reads VOYAGE_API_KEY

result = voyage.embed(["chunk 1 text", "chunk 2 text"], model="voyage-4", input_type="document")
vectors = result.embeddings  # list[list[float]]
```

Use `input_type="document"` when indexing, `input_type="query"` when searching — the embedding targets differ and mixing them costs recall. Refer to `model-capabilities.md` for the current Voyage 4 family (`voyage-4-large`, `voyage-4`, `voyage-4-lite`, `voyage-4-nano`) and dimension defaults.
