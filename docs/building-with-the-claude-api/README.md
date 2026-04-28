# Building with the Claude API — Curriculum Index

A 62-lesson curriculum on building production applications with the Claude API and the Anthropic Python SDK. Lessons are flat-numbered for easy navigation; this index groups them into reading sections.

> **Looking something up?** Read the section-sized reference notes in `.claude/skills/building-with-the-claude-api/references/` — one file per section, denser and built for re-reading. The lessons below are the original tutorial, best read linearly the first time.

> **For Claude Code agents:** the matching skill at `.claude/skills/building-with-the-claude-api/` is the navigator — load only the reference file you need, not the raw lessons.

> **Note on numbering:** lesson `35a-implementing-the-RAG-flow.md` was originally mislabelled `26-…` and conflicted with `26-multi-turn-conversations-with-tools.md`. It now sits next to the rest of the RAG section. There are no other numbering gaps; the sequence runs 1 → 62 with `35a` inserted after lesson 35.

---

## 1. API Basics (lessons 1–7)
Set up the SDK, send your first request, hold a multi-turn conversation, control the model.

| # | Lesson |
|---|---|
| 1 | [Accessing the API](1-accessing-the-api.md) |
| 2 | [Getting an API key](2-getting-an-api-key.md) |
| 3 | [Making a request](3-making-a-request.md) |
| 4 | [Multi-turn conversations](4-multi-turn-conversations.md) |
| 5 | [System prompts](5-system-prompts.md) |
| 6 | [Temperature](6-temperature.md) |
| 7 | [Response streaming](7-response-streaming.md) |

## 2. Structured Output & Evaluation (lessons 8–14)
Get JSON out reliably, then build an eval loop to measure prompt quality.

| # | Lesson |
|---|---|
| 8 | [Structured data](8-structured-data.md) |
| 9 | [Prompt evaluation](9-prompt-evaluation.md) |
| 10 | [A typical eval workflow](10-a-typical-eval-workflow.md) |
| 11 | [Generating test datasets](11-generating-test-datasets.md) |
| 12 | [Running the eval](12-running-the-eval.md) |
| 13 | [Model-based grading](13-model-based-grading.md) |
| 14 | [Code-based grading](14-code-based-grading.md) |

## 3. Prompt Engineering (lessons 15–19)
Techniques that move accuracy without changing the model.

| # | Lesson |
|---|---|
| 15 | [Prompt engineering](15-prompt-engineering.md) |
| 16 | [Being clear and direct](16-being-clear-and-direct.md) |
| 17 | [Being specific](17-being-specific.md) |
| 18 | [Structure with XML tags](18-structure-with-xml-tags.md) |
| 19 | [Prompt example](19-prompt-example.md) |

## 4. Tool Use (lessons 20–31)
Function calling, multi-turn tool loops, parallel tool calls, and built-in tools.

| # | Lesson |
|---|---|
| 20 | [Introducing tool use](20-introducing-tool-use.md) |
| 21 | [Project overview](21-project-overview.md) |
| 22 | [Tool functions](22-tool-functions.md) |
| 23 | [Tool schemas](23-tool-schemas.md) |
| 24 | [Handling message blocks](24-handling-message-blocks.md) |
| 25 | [Sending tool results](25-sending-tool-results.md) |
| 26 | [Multi-turn conversations with tools](26-multi-turn-conversations-with-tools.md) |
| 27 | [Implementing multiple turns](27-implementing-multiple-turns.md) |
| 28 | [Using multiple tools](28-using-multiple-tools.md) |
| 29 | [Fine-grained tool calling](29-fine-grained-tool-calling.md) |
| 30 | [The text-edit tool](30-the-text-edit-tool.md) |
| 31 | [The web-search tool](31-the-web-search-tool.md) |

## 5. RAG — Retrieval-Augmented Generation (lessons 32–37)
Chunking, embeddings, semantic and lexical retrieval, hybrid pipelines.

| # | Lesson |
|---|---|
| 32 | [Introducing RAG](32-introducing-retrieval-augmented-generation.md) |
| 33 | [Text chunking strategies](33-text-chunking-strategies.md) |
| 34 | [Text embeddings](34-text-embeddings.md) |
| 35 | [The full RAG flow](35-the-full-RAG-flow.md) |
| 35a | [Implementing the RAG flow (walkthrough)](35a-implementing-the-RAG-flow.md) |
| 36 | [BM25 lexical search](36-BM25-lexical-search.md) |
| 37 | [A multi-index RAG pipeline](37-a-multi-index-RAG-pipeline.md) |

## 6. Advanced Features (lessons 38–45)
Thinking, vision, PDFs, citations, prompt caching, code execution.

| # | Lesson |
|---|---|
| 38 | [Extended thinking](38-extended-thinking.md) |
| 39 | [Image support](39-image-support.md) |
| 40 | [PDF support](40-PDF-support.md) |
| 41 | [Citations](41-citations.md) |
| 42 | [Prompt caching](42-prompt-caching.md) |
| 43 | [Rules of prompt caching](43-rules-of-prompt-caching.md) |
| 44 | [Prompt caching in action](44-prompt-caching-in-action.md) |
| 45 | [Code execution and the Files API](45-code-execution-and-the-files-API.md) |

## 7. MCP — Model Context Protocol (lessons 46–55)
Build clients and servers, expose tools / resources / prompts, debug with the Inspector.

| # | Lesson |
|---|---|
| 46 | [Introducing MCP](46-introducing-MCP.md) |
| 47 | [MCP clients](47-MCP-clients.md) |
| 48 | [Project setup](48-project-setup.md) |
| 49 | [The server inspector](49-the-server-inspector.md) |
| 50 | [Implementing a client](50-implementing-a-client.md) |
| 51 | [Defining resources](51-defining-resources.md) |
| 52 | [Accessing resources](52-accessing-resources.md) |
| 53 | [Designing prompts](53-designing-prompts.md) |
| 54 | [Prompts in the client](54-prompts-in-the-client.md) |
| 55 | [MCP review](55-MCP-review.md) |

## 8. Agents & Workflows (lessons 56–62)
When to compose multi-step workflows, when to give Claude tools and a goal, and how to choose.

| # | Lesson |
|---|---|
| 56 | [Agents and workflows](56-agents-and-workflows.md) |
| 57 | [Parallelization workflows](57-parallelization-workflows.md) |
| 58 | [Chaining workflows](58-chaining-workflows.md) |
| 59 | [Routing workflows](59-routing-workflows.md) |
| 60 | [Agents and tools](60-agents-and-tools.md) |
| 61 | [Environment inspection](61-environment-inspection.md) |
| 62 | [Workflows vs agents](62-workflows-vs-agents.md) |

---

## How to read

- **First time through:** read in order, sections 1 → 8.
- **Looking up a topic:** jump straight to the lesson via the index above, or use the skill at `.claude/skills/building-with-the-claude-api/SKILL.md` which routes to consolidated reference notes per section.
- **Building something specific:** open the section README (this file) for orientation, then the consolidated reference in `references/` of the skill — those are denser and built for re-reading, while the lessons are the original teaching material.
