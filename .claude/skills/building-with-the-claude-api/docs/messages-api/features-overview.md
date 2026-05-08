# Features overview

Explore Claude's advanced features and capabilities.

> See also: [ADDITIONAL-NOTES.md § D2](../ADDITIONAL-NOTES.md#d2--1-building-with-claude1-features-overviewmd) — inside Claude's processing (tokenization → embedding → contextualization → generation).

---

Claude's API surface is organized into five areas:

- **Model capabilities:** Control how Claude reasons and formats responses.
- **Tools:** Let Claude take actions on the web or in your environment.
- **Tool infrastructure:** Handles discovery and orchestration at scale.
- **Context management:** Keeps long-running sessions efficient.
- **Files and assets:** Manage the documents and data you provide to Claude.

If you're new, start with [model capabilities](#model-capabilities) and [tools](#tools). Return to the other sections when you're ready to optimize cost, latency, or scale.

## Feature availability

Features on the Claude Platform are assigned one of the following availability classifications per platform (shown in the Availability column of each table below). Not all features pass through every stage. A feature may enter at any classification and may skip stages.

| Classification | Description |
|----------------|-------------|
| **Beta**<sup>*</sup> | Preview features used for gathering feedback and iterating on a less mature use case. Availability may be limited, including through sign-up requirements or waitlists, and may not be publicly announced. <br/><br/> Features may change significantly or be discontinued based on feedback. Not guaranteed for ongoing production use. Breaking changes are possible with notice, and some platform-specific limitations may apply. Beta features have a [beta header](https://docs.claude.com/en/docs/api/beta-headers). |
| **Generally available (GA)** | Feature is stable, fully supported, and recommended for production use. Should not have a beta header or other indicator that the feature is in a preview state. Covered by standard API [versioning](https://docs.claude.com/en/docs/api/versioning) guarantees. |
| **Deprecated** | Feature is still functional but no longer recommended. A migration path and removal timeline are provided. |
| **Retired** | Feature is no longer available. |

_<sup>*</sup> May carry a qualifier indicating narrower availability or added constraints (for example, "beta: research preview"). See the feature's page for details._

## Model capabilities

Ways to steer Claude and Claude's direct outputs, including response format, reasoning depth, and input modalities.

<Tip>
You can discover which capabilities a model supports programmatically. The [Models API](https://docs.claude.com/en/docs/api/models/list) returns `max_input_tokens`, `max_tokens`, and a `capabilities` object for every available model.
</Tip>

| Feature | Description | Zero Data Retention (ZDR) | Availability |
|---------|-----------|----|--------------|
| [Context windows](../context-management/context-windows.md) | Up to 1M tokens for processing large documents, extensive codebases, and long conversations. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Adaptive thinking](../model-capabilities/adaptive-thinking.md) | Let Claude dynamically decide when and how much to think. The recommended thinking mode for Opus 4.7. Use the effort parameter to control thinking depth. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Batch processing](../model-capabilities/batch-processing.md) | Process large volumes of requests asynchronously for cost savings. Send batches with a large number of queries per batch. Batch API calls cost 50% less than standard API calls. | Not ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi /> |
| [Citations](../model-capabilities/citations.md) | Ground Claude's responses in source documents. With Citations, Claude can provide detailed references to the exact sentences and passages it uses to generate responses, leading to more verifiable, trustworthy outputs. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Data residency](../admin/data-compliance/data-residency.md) | Control where model inference runs using geographic controls. Specify `"global"` or `"us"` routing per request via the `inference_geo` parameter. | ZDR eligible | <PlatformAvailability claudeApi /> |
| [Effort](../model-capabilities/effort.md) | Control how many tokens Claude uses when responding with the effort parameter, trading off between response thoroughness and token efficiency. Supported on Opus 4.7, Opus 4.6, and Opus 4.5. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Extended thinking](../model-capabilities/extended-thinking.md) | Enhanced reasoning capabilities for complex tasks, providing transparency into Claude's step-by-step thought process before delivering its final answer. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [PDF support](../files/pdf-support.md) | Process and analyze text and visual content from PDF documents. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Search results](../model-capabilities/search-results.md) | Enable natural citations for RAG applications by providing search results with proper source attribution. Achieve web search-quality citations for custom knowledge bases and tools. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Structured outputs](../model-capabilities/structured-outputs.md) | Guarantee schema conformance with two approaches: JSON outputs for structured data responses, and strict tool use for validated tool inputs. | [ZDR eligible (qualified)](../model-capabilities/structured-outputs.md#data-retention)* | <PlatformAvailability claudeApi bedrock azureAiBeta /> |

## Tools

Built-in tools that Claude invokes via `tool_use`. Server-side tools are run by the platform; client-side tools are implemented and executed by you.

### Server-side tools

| Feature | Description | ZDR | Availability |
|---------|-----------|----|--------------|
| [Advisor tool](../tools/advisor-tool.md) | Pair a faster executor model with a higher-intelligence advisor model that provides strategic guidance mid-generation for long-horizon agentic workloads. | ZDR eligible | <PlatformAvailability claudeApiBeta /> |
| [Code execution](../tools/code-execution-tool.md) | Run code in a sandboxed environment for advanced data analysis, calculations, and file processing. Free when used with web search or web fetch. | Not ZDR eligible | <PlatformAvailability claudeApi azureAiBeta /> |
| [Web fetch](../tools/web-fetch-tool.md) | Retrieve full content from specified web pages and PDF documents for in-depth analysis. | ZDR eligible* | <PlatformAvailability claudeApi azureAiBeta /> |
| [Web search](../tools/web-search-tool.md) | Augment Claude's comprehensive knowledge with current, real-world data from across the web. | ZDR eligible* | <PlatformAvailability claudeApi vertexAi azureAiBeta /> |

### Client-side tools

| Feature | Description | ZDR | Availability |
|---------|-----------|----|--------------|
| [Bash](../tools/bash-tool.md) | Execute bash commands and scripts to interact with the system shell and perform command-line operations. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Computer use](../tools/computer-use-tool.md) | Control computer interfaces by taking screenshots and issuing mouse and keyboard commands. | ZDR eligible | <PlatformAvailability claudeApiBeta bedrockBeta vertexAiBeta azureAiBeta /> |
| [Memory](../tools/memory-tool.md) | Enable Claude to store and retrieve information across conversations. Build knowledge bases over time, maintain project context, and learn from past interactions. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Text editor](../tools/text-editor-tool.md) | Create and edit text files with a built-in text editor interface for file manipulation tasks. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |

## Tool infrastructure

Infrastructure that supports discovering, orchestrating, and scaling tool use.

| Feature | Description | ZDR | Availability |
|---------|-----------|----|--------------|
| [Agent Skills](../skills/agent-skills-overview.md) | Extend Claude's capabilities with Skills. Use pre-built Skills (PowerPoint, Excel, Word, PDF) or create custom Skills with instructions and scripts. Skills use progressive disclosure to efficiently manage context. | Not ZDR eligible | <PlatformAvailability claudeApiBeta azureAiBeta /> |
| [Fine-grained tool streaming](../tool-infrastructure/fine-grained-tool-streaming.md) | Stream tool use parameters without buffering/JSON validation, reducing latency for receiving large parameters. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [MCP connector](../mcp/mcp-connector.md) | Connect to remote [MCP](../mcp/index.md) servers directly from the Messages API without a separate MCP client. | Not ZDR eligible | <PlatformAvailability claudeApiBeta azureAiBeta /> |
| [Programmatic tool calling](../tool-infrastructure/programmatic-tool-calling.md) | Enable Claude to call your tools programmatically from within code execution containers, reducing latency and token consumption for multi-tool workflows. | Not ZDR eligible | <PlatformAvailability claudeApi azureAiBeta /> |
| [Tool search](../tool-infrastructure/tool-search-tool.md) | Scale to thousands of tools by dynamically discovering and loading tools on-demand using regex-based search, optimizing context usage and improving tool selection accuracy. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |

## Context management

Infrastructure for controlling and optimizing Claude's context window.

| Feature | Description | ZDR | Availability |
|---------|-----------|----|--------------|
| [Compaction](../context-management/compaction.md) | Server-side context summarization for long-running conversations. When context approaches the window limit, the API automatically summarizes earlier parts of the conversation. Supported on Opus 4.7, Opus 4.6, and Sonnet 4.6. | ZDR eligible | <PlatformAvailability claudeApiBeta bedrockBeta vertexAiBeta azureAiBeta /> |
| [Context editing](../context-management/context-editing.md) | Automatically manage conversation context with configurable strategies. Supports clearing tool results when approaching token limits and managing thinking blocks in extended thinking conversations. | ZDR eligible | <PlatformAvailability claudeApiBeta bedrockBeta vertexAiBeta azureAiBeta /> |
| [Automatic prompt caching](../context-management/prompt-caching.md#automatic-caching) | Simplify prompt caching to a single API parameter. The system automatically caches the last cacheable block in your request, moving the cache point forward as conversations grow. | ZDR eligible | <PlatformAvailability claudeApi azureAiBeta /> |
| [Prompt caching (5m)](../context-management/prompt-caching.md) | Provide Claude with more background knowledge and example outputs to reduce costs and latency. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |
| [Prompt caching (1hr)](../context-management/prompt-caching.md#1-hour-cache-duration) | Extended 1-hour cache duration for less frequently accessed but important context, complementing the standard 5-minute cache. | ZDR eligible | <PlatformAvailability claudeApi vertexAi azureAiBeta /> |
| [Token counting](https://docs.claude.com/en/docs/api/messages-count-tokens) | Token counting enables you to determine the number of tokens in a message before sending it to Claude, helping you make informed decisions about your prompts and usage. | ZDR eligible | <PlatformAvailability claudeApi bedrock vertexAi azureAiBeta /> |

## Files and assets

Manage files and assets for use with Claude.

| Feature | Description | ZDR | Availability |
|---------|-----------|----|--------------|
| [Files API](../files/files-api.md) | Upload and manage files to use with Claude without re-uploading content with each request. Supports PDFs, images, and text files. | Not ZDR eligible | <PlatformAvailability claudeApiBeta azureAiBeta /> |

\* **Structured outputs:** Your prompts and Claude's outputs are not stored. Only JSON schemas are cached, for up to 24 hours since last use. **Web search and web fetch:** ZDR-eligible except when [dynamic filtering](../tools/web-search-tool.md#dynamic-filtering) is enabled. See [ZDR details](../admin/data-compliance/api-and-data-retention.md#feature-eligibility).