# impl-15: Conversational Agent Mode (5.1)

**Status:** Phase 1-4 Complete · Phase 5+ Planned
**Priority:** 5.1
**Impact:** Transform from deterministic pipeline to interactive AI agent — Jira comments become a chat interface for multi-turn, multi-tool orchestration with MCP extensibility

---

## Goal

Enable conversational AI interaction through Jira ticket comments:
1. Developers communicate with AI via Jira comments (natural language)
2. AI interprets intent, selects tools dynamically, executes autonomously
3. AI responds via Jira comments with results, questions, or status updates
4. Multi-turn: AI remembers full conversation context per ticket
5. Multi-actor: Team members can provide feedback, request changes, ask questions — AI responds accordingly

### User Story

```
As a developer, I want to:
1. Create ticket ONC-10001: "Backend 500 errors on /api/payments since last deploy"
2. Comment: "@ai investigate the root cause from Datadog logs"
   → AI searches Datadog, finds NullPointerException in PaymentService.process()
   → AI comments: "Found root cause: NPE at PaymentService.java:142. The `discountRate`
      field is null when payment type is CREDIT. Introduced in commit abc1234."
3. Comment: "@ai create branch ai/ONC-10001-fix-npe and fix this issue"
   → AI creates branch, commits fix with null check + unit test, opens PR
   → AI comments: "PR #342 created: https://github.com/org/repo/pull/342"
4. Comment: "@ai notify the backend team on Slack to review this PR"
   → AI sends Slack message to #backend-team with PR link and summary
5. Teammate comments: "Use Optional instead of null check for discountRate"
   → AI detects feedback, updates PR with Optional-based approach
   → AI comments: "Updated PR with Optional<BigDecimal> for discountRate. PTAL."
```

---

## Architecture Overview

### Current vs Agent Mode

```
CURRENT (Pipeline Mode):
  Jira webhook → Step Functions → [Fetch → Context → Claude → PR → Wait] → Done
  - Fixed 5-step workflow
  - Single Claude invocation
  - No conversation state

AGENT MODE:
  Jira comment webhook → Agent Orchestrator → Claude (with tools) → Execute → Comment back
                              ↑                                          │
                              └──────── next comment ───────────────────┘
  - Unbounded conversation loop
  - Multiple Claude invocations with tool use
  - Persistent conversation state per ticket
  - Claude decides which tools to call
```

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Jira (Chat Interface)                     │
│  Comment: "@ai investigate logs"                            │
│  Comment: "@ai fix it and notify team"                      │
└──────────────────┬──────────────────────────────────────────┘
                   │ Jira Webhook (comment_created)
                   ▼
┌──────────────────────────────────────────────────────────────┐
│              AgentWebhookController (Spring Boot)             │
│  1. Validate webhook (comment event, not bot-authored)       │
│  2. Check if ticket has "ai-agent" label                     │
│  3. Route to AgentOrchestrator (in `core` module)            │
│  4. Wiring: Injects specific ToolProviders from modules      │
└──────────────────┬───────────────────────────────────────────┘
                   ▼
┌──────────────────────────────────────────────────────────────┐
│            AgentOrchestrator (in `core` module)              │
│                                                              │
│  1. Load conversation history (DynamoDB)                     │
│  2. Classify comment intent (AI_COMMAND / FEEDBACK / Q&A)    │
│  3. Build Messages API request:                              │
│     - System prompt (agent persona + tool instructions)      │
│     - Conversation history (sliding window + summary)        │
│     - Available tools (from ToolRegistry → ToolProviders)     │
│     - New comment as latest user message                     │
│  4. Call Claude Messages API with tool_use                   │
│  5. Execute tool calls returned by Claude                    │
│  6. If Claude returns more tool_use → loop (max N turns)     │
│  7. Post Claude's final text response as Jira comment        │
│  8. Persist updated conversation state                       │
└──────────────────┬───────────────────────────────────────────┘
                   │ Claude selects tools dynamically
                   ▼
┌──────────────────────────────────────────────────────────────┐
│     GuardedToolRegistry (Phase 3: risk gate decorator)       │
│  ┌──────────────────────────────────────────────────────────┐│
│  │          ToolRegistry (routes by namespace)               ││
│  │                                                          ││
│  │  ┌─ SourceControlToolProvider ─┐ ┌─ IssueTrackerTP ────┐││
│  │  │  namespace: source_control  │ │  namespace: issue_*  │││
│  │  │  wraps: SourceControlClient │ │  wraps: JiraClient   │││
│  │  │  • create_branch            │ │  • get_ticket        │││
│  │  │  • commit_files / create_pr │ │  • add_comment       │││
│  │  │  • get_file / search_files  │ │  • update_status     │││
│  │  │  • merge_pr ⚠️ HIGH risk    │ │                      │││
│  │  └─────────────────────────────┘ └──────────────────────┘││
│  │                                                          ││
│  │  ┌─ CodeContextToolProvider ───┐                         ││
│  │  │  namespace: code_context    │                         ││
│  │  │  wraps: ContextService      │                         ││
│  │  │  • search_code / get_file   │                         ││
│  │  └─────────────────────────────┘                         ││
│  │                                                          ││
│  │  ┌─ McpBridgeToolProvider ──── (Phase 4: MCP Gateway) ──┐││
│  │  │  One instance per MCP server. Tools auto-discovered.  │││
│  │  │  namespace: {config}  │  wraps: McpSyncClient         │││
│  │  │  e.g., monitoring_*   │  via MCP Java SDK             │││
│  │  │  e.g., messaging_*    │  stdio / HTTP transport       │││
│  │  │  e.g., data_*         │  secrets from AWS SM          │││
│  │  └───────────────────────────────────────────────────────┘││
│  └──────────────────────────────────────────────────────────┘│
│  ToolRiskRegistry → ActionPolicy (LOW/MEDIUM auto, HIGH gate)│
│  ApprovalStore (DynamoDB) ← pending HIGH-risk actions        │
│  CostTracker (DynamoDB)   ← per-ticket token budget          │
└──────────────────────────────────────────────────────────────┘
```

### Modular Monolith Architecture

The agent is built as a **Modular Monolith** to ensure flexibility and separation of concerns without the operational overhead of microservices.

*   **`core` Module**: Contains `AgentOrchestrator`, `ToolRegistry`, `ToolProvider` interface, guardrails (`GuardedToolRegistry`, `ToolRiskRegistry`, `ApprovalStore`), `CostTracker`, and `CommentIntentClassifier`.
*   **`tool-source-control` Module**: Contains `SourceControlToolProvider` and depends on `core`.
*   **`tool-issue-tracker` Module**: Contains `IssueTrackerToolProvider` and depends on `core`.
*   **`tool-code-context` Module**: Contains `CodeContextToolProvider` and depends on `core`.
*   **`mcp-bridge` Module** (Phase 4): Contains `McpBridgeToolProvider` and `McpConnectionFactory`. Bridges ANY MCP server into the `ToolProvider` contract via the MCP Java SDK.
*   **`spring-boot-app` Module**: Depends on `core`, `tool-*` modules, `spi`, and `mcp-bridge` to assemble the full agent via Spring Boot configuration.

### Trade-offs (Counter-arguments)

While the Modular Monolith structure provides good separation of concerns, it does have some trade-offs compared to a microservices or plugin-based architecture:

1.  **Deployment Coupling**: All tools (`source-control`, `issue-tracker`, etc.) are bundled into a single Spring Boot application. A change in one module requires redeploying the entire agent.
2.  **Build-Time Composition**: Tools are wired together at build time via Spring `@Configuration` classes. Adding a new tool requires code changes to `AgentConfig` and a recompile; it is not a dynamic runtime plugin system.
3.  **Single Failure Domain**: Since all tools run in the same process, a critical error (like an OutOfMemoryError) in one tool provider could crash the entire request processing, affecting other tools.
4.  **Shared Runtime Characteristics**: All tools must share the same memory and timeout configuration of the Lambda function. A memory-intensive tool might force over-provisioning for lightweight tools.

**Why this is acceptable for Phase 1-2:**
The operational simplicity of managing a single Lambda function and a unified build process outweighs the flexibility of independent deployments at this stage. The shared `core` ensures consistency, and the module boundaries prevent spaghetti code, making a future split into microservices easier if scale demands it.

---

## Proposed Changes

### Phase 1: Single-Turn Agent (MVP)

Each comment is processed independently. No conversation memory. Validates the core loop: webhook → Claude + tools → comment back.

#### 1.1 Agent Webhook Handler

- [x] Create `AgentWebhookHandler` Lambda
  - Triggered by Jira webhook on `comment_created` event
  - Validate: ticket has `ai-agent` label
  - Filter: skip comments authored by the bot (prevent infinite loop)
  - Filter: only process comments starting with `@ai` (configurable prefix)
  - Extract: `ticketKey`, `commentBody`, `commentAuthor`, `commentId`
  - **Post immediate ack comment** (see 1.9 Progress Feedback)
  - Enqueue to SQS FIFO queue (see 1.10 Comment Ordering)

#### 1.2 Comment Intent Router

- [x] Create `CommentIntentClassifier` in `core` module
  ```java
  public enum CommentIntent {
      AI_COMMAND,      // "@ai do something"
      HUMAN_FEEDBACK,  // Reviewer feedback on AI's work
      QUESTION,        // Question about AI's work or the ticket
      APPROVAL,        // "LGTM", "approved", "merge it"
      IRRELEVANT       // Unrelated to AI
  }
  ```
- [x] Detection strategy (Phase 1 — rule-based):
  - `@ai` prefix → `AI_COMMAND`
  - Reply to AI comment + contains question mark → `QUESTION`
  - Reply to AI comment + mentions code change → `HUMAN_FEEDBACK`
  - Contains "LGTM", "approved", "merge" → `APPROVAL`
  - Default → `IRRELEVANT` (skip processing)

#### 1.3 ToolProvider Interface (Hybrid Adapter Pattern)

Each integration exposes its capabilities through a `ToolProvider` — combining typed domain
interfaces (compile-time safety) with a unified registration contract (extensibility).

- [x] Create `ToolProvider` interface in `core` module
  ```java
  /**
   * Contract for exposing domain client capabilities as Claude tool-use tools.
   * Each implementation wraps a typed domain interface (SourceControlClient,
   * MonitoringClient, etc.) and bridges it to Claude's tool-use protocol.
   *
   * Design rationale: Avoids a monolithic ToolExecutor switch statement.
   * Adding a new integration = one new ToolProvider class + register in factory.
   * Zero changes to AgentOrchestrator or ToolRegistry.
   */
  public interface ToolProvider {

      /** Namespace prefix for all tools (e.g., "source_control", "monitoring"). */
      String namespace();

      /** Tool definitions in Claude Messages API format. */
      List<Tool> toolDefinitions();

      /** Dispatch a tool call to the underlying typed client. */
      ToolResult execute(ToolCall call);

      /**
       * Max output size in characters per tool result (default: 20,000).
       * Each provider should override to truncate domain-appropriately:
       * - Logs: keep first/last N lines + total count
       * - File trees: depth-2 summary + file count
       * - Query results: first 50 rows + schema + total row count
       * - Code files: full content (usually under limit)
       */
      default int maxOutputChars() { return 20_000; }
  }
  ```

- [x] Create `ToolRegistry` — aggregates all providers, routes tool calls by namespace
  ```java
  public class ToolRegistry {

      private final Map<String, ToolProvider> providers = new LinkedHashMap<>();

      public void register(ToolProvider provider) {
          providers.put(provider.namespace(), provider);
      }

      /** All tool definitions across all registered providers (for Claude API). */
      public List<Tool> getAllToolDefinitions() {
          return providers.values().stream()
              .flatMap(p -> p.toolDefinitions().stream())
              .toList();
      }

      /** Filtered tools based on ticket config (labels, integrations). */
      public List<Tool> getAvailableTools(TicketInfo ticket) {
          return providers.entrySet().stream()
              .filter(e -> isProviderEnabled(e.getKey(), ticket))
              .flatMap(e -> e.getValue().toolDefinitions().stream())
              .toList();
      }

      /** Route tool call to the correct provider by namespace prefix. */
      public ToolResult execute(ToolCall call) {
          String namespace = extractNamespace(call.name());
          ToolProvider provider = providers.get(namespace);
          if (provider == null) {
              return ToolResult.error("No provider registered for namespace: " + namespace);
          }
          return provider.execute(call);
      }

      private String extractNamespace(String toolName) {
          // "source_control_create_branch" → "source_control"
          // Strategy: match longest registered namespace prefix
          return providers.keySet().stream()
              .filter(toolName::startsWith)
              .max(Comparator.comparingInt(String::length))
              .orElse("unknown");
      }

      private boolean isProviderEnabled(String namespace, TicketInfo ticket) {
          // Core providers always enabled
          if (Set.of("source_control", "issue_tracker", "code_context").contains(namespace)) {
              return true;
          }
          // Optional providers enabled by ticket labels: "tool:datadog", "tool:slack"
          if (ticket.getLabels() == null) return false;
          return ticket.getLabels().stream()
              .anyMatch(label -> label.equalsIgnoreCase("tool:" + namespace));
      }
  }
  ```

- [x] Implement `SourceControlToolProvider` — wraps existing `SourceControlClient`
  ```java
  public class SourceControlToolProvider implements ToolProvider {

      private final SourceControlClient client;
      private final ObjectMapper mapper;

      public SourceControlToolProvider(SourceControlClient client, ObjectMapper mapper) {
          this.client = client;
          this.mapper = mapper;
      }

      @Override
      public String namespace() { return "source_control"; }

      @Override
      public List<Tool> toolDefinitions() {
          return List.of(
              Tool.of("source_control_create_branch",
                  "Create a new Git branch from an existing branch",
                  Schema.object()
                      .required("branch_name", Schema.string("New branch name"))
                      .optional("from_branch", Schema.string("Source branch (default: main)"))
              ),
              Tool.of("source_control_commit_files",
                  "Commit file changes to a branch",
                  Schema.object()
                      .required("branch_name", Schema.string("Target branch"))
                      .required("files", Schema.array("Files to commit with path and content"))
                      .required("commit_message", Schema.string("Commit message"))
              ),
              Tool.of("source_control_create_pr",
                  "Create a pull request",
                  Schema.object()
                      .required("title", Schema.string("PR title"))
                      .required("description", Schema.string("PR description"))
                      .required("source_branch", Schema.string("Feature branch"))
                      .optional("target_branch", Schema.string("Base branch (default: main)"))
              ),
              Tool.of("source_control_get_file",
                  "Get the content of a file from a branch",
                  Schema.object()
                      .required("branch", Schema.string("Branch name"))
                      .required("file_path", Schema.string("Path to the file"))
              ),
              Tool.of("source_control_search_files",
                  "Search for files matching a query in the repository",
                  Schema.object()
                      .required("query", Schema.string("Search query"))
              )
          );
      }

      @Override
      public ToolResult execute(ToolCall call) {
          try {
              return switch (call.name()) {
                  case "source_control_create_branch" -> {
                      var input = call.input();
                      String branchName = input.get("branch_name").asText();
                      String fromBranch = input.path("from_branch").asText("main");
                      client.createBranch(branchName, fromBranch);
                      yield ToolResult.success("Branch '%s' created from '%s'", branchName, fromBranch);
                  }
                  case "source_control_commit_files" -> {
                      var input = call.input();
                      var files = mapper.convertValue(
                          input.get("files"),
                          new TypeReference<List<AgentResult.GeneratedFile>>() {}
                      );
                      String hash = client.commitFiles(
                          input.get("branch_name").asText(),
                          files,
                          input.get("commit_message").asText()
                      );
                      yield ToolResult.success("Committed %d files. Hash: %s", files.size(), hash);
                  }
                  case "source_control_create_pr" -> {
                      var input = call.input();
                      var result = client.createPullRequest(
                          input.get("title").asText(),
                          input.get("description").asText(),
                          input.get("source_branch").asText(),
                          input.path("target_branch").asText("main")
                      );
                      yield ToolResult.success("PR #%s created: %s", result.id(), result.url());
                  }
                  case "source_control_get_file" -> {
                      var input = call.input();
                      String content = client.getFileContent(
                          input.get("branch").asText(),
                          input.get("file_path").asText()
                      );
                      yield content != null
                          ? ToolResult.success(content)
                          : ToolResult.error("File not found");
                  }
                  case "source_control_search_files" -> {
                      List<String> files = client.searchFiles(call.input().get("query").asText());
                      yield ToolResult.success("Found %d files:\n%s", files.size(), String.join("\n", files));
                  }
                  default -> ToolResult.error("Unknown tool: " + call.name());
              };
          } catch (Exception e) {
              return ToolResult.error("Tool execution failed: " + e.getMessage());
          }
      }
  }
  ```

- [x] Implement `IssueTrackerToolProvider` — wraps existing `IssueTrackerClient`
- [x] Implement `CodeContextToolProvider` — wraps existing `ContextService`

**Why this pattern over a monolithic ToolExecutor:**
- **Open/Closed:** Adding Datadog = new `DatadogToolProvider` + `registry.register(...)`. Zero changes to orchestrator, registry, or other providers.
- **Type safety preserved:** Each provider calls typed domain methods (`client.searchLogs(LogQuery)`) — no `Map<String, Object>` casting.
- **Testable in isolation:** Unit test each provider with a mocked domain client. No god-object to wire up.
- **MCP-ready:** Each `ToolProvider` maps 1:1 to a future MCP tool server.

#### 1.4 Agent Orchestrator

- [x] Create `AgentOrchestrator` service
  ```java
  public class AgentOrchestrator {

      private static final int MAX_TOOL_TURNS = 10;

      private final AiClient aiClient;
      private final ToolRegistry toolRegistry;
      private final JiraCommentFormatter formatter;

      public AgentResponse process(AgentRequest request) {
          // 1. Build system prompt
          String systemPrompt = buildSystemPrompt(request.ticket());

          // 2. Build messages (Phase 1: just the current comment)
          List<Message> messages = new ArrayList<>();
          messages.add(Message.user(request.comment().body()));

          // 3. Get available tools filtered by ticket config
          List<Tool> tools = toolRegistry.getAvailableTools(request.ticket());

          // 4. Agentic loop — Claude decides tools, we execute
          for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
              ClaudeResponse response = claudeClient.chat(systemPrompt, messages, tools);

              if (response.stopReason().equals("end_turn")) {
                  return AgentResponse.text(response.textContent());
              }

              if (response.stopReason().equals("tool_use")) {
                  // Dispatch each tool call via registry (output auto-truncated per provider)
                  List<ToolResult> results = response.toolCalls().stream()
                      .map(toolRegistry::execute)
                      .toList();

                  // Update progress comment: "✅ Searched logs, ⏳ Creating branch..."
                  progressTracker.updateProgress(request.ackCommentId(), results);

                  messages.add(Message.assistant(response.rawContent()));
                  messages.add(Message.user(toToolResultBlocks(results)));
              }

              // Circuit breaker: if approaching Lambda timeout, stop and post partial results
              if (wallClock.elapsed() > MAX_WALL_CLOCK) {
                  return AgentResponse.partial(
                      "Completed %d/%d steps. Will continue on next interaction.",
                      turn + 1, MAX_TOOL_TURNS
                  );
              }
          }

          return AgentResponse.text("Reached max tool turns. Partial results: ...");
      }
  }
  ```

#### 1.6 Claude Client Extension (Tool Use Support)

- [x] Extend AI client to support tool-use API (now via `SpringAiClientAdapter`):
  ```java
  public ClaudeResponse chat(String system, List<Message> messages, List<Tool> tools) {
      // Build request body with tools array
      // Handle stop_reason: "tool_use" vs "end_turn"
      // Parse tool_use content blocks
  }
  ```
- [x] Add model classes:
  - `Tool` (name, description, input_schema)
  - `ToolCall` (id, name, input)
  - `ToolResult` (tool_use_id, content, is_error)
  - `Message` (role, content blocks)

#### 1.7 Response Formatter

- [x] Create `JiraCommentFormatter` to convert Claude's response to Jira-friendly markdown
  - Convert code blocks to Jira `{code}` blocks
  - Convert markdown links to Jira link syntax
  - Add metadata footer (model, tools used, duration)
  - Truncate to Jira comment size limit (32,768 chars)

#### 1.8 Progress Feedback (Ack Comment)

Addresses "The Silent Gap" — users don't know if the AI received their request.

- [x] Create `ProgressTracker` service
  - On webhook receipt: immediately post an ack comment via Jira API
    ```
    🔄 Working on your request...
    ```
  - As each tool completes: **edit the same comment in-place** (Jira update comment API)
    ```
    🔄 Working on your request...
      ✅ Searched Datadog logs (found 3 matches)
      ✅ Identified root cause: NPE in PaymentService.java:142
      ⏳ Creating branch and fix...
    ```
  - On completion: replace with final response (or post as new comment, keep progress as history)
  - On timeout/error: update ack comment with partial results and error info
- [x] Store `ackCommentId` in `AgentRequest` so orchestrator can update it

#### 1.9 Comment Ordering (FIFO SQS)

Prevents race conditions when multiple comments arrive in rapid succession.

- [x] Create SQS FIFO queue: `ai-driven-agent-tasks.fifo` (CDK: `AgentQueueStack.ts`)
  - `MessageGroupId`: ticket key (e.g., `ONC-10001`) — ensures FIFO per ticket
  - `ContentBasedDeduplication`: enabled (prevents duplicate webhook deliveries)
  - `VisibilityTimeout`: 300 seconds (5 min — matches max agent turn duration)
  - Dead-letter queue: `ai-driven-agent-tasks-dlq.fifo` (maxReceiveCount: 3)
- [x] Flow:
  ```
  Jira Webhook → AgentWebhookHandler (validate + ack comment) → SQS FIFO
       → AgentProcessorHandler (Lambda, triggered by SQS) → AgentOrchestrator
  ```
- [x] `AgentWebhookHandler` becomes thin: validate, ack, enqueue
- [x] `AgentProcessorHandler` (new): dequeue, load context, run orchestrator, post response
- [x] Benefit: Comment A fully completes (including AI reply) before Comment B starts processing

#### 1.10 CDK Infrastructure

- [x] Add `AgentWebhookHandler` Lambda (thin: validate + enqueue)
- [x] Add `AgentProcessorHandler` Lambda (heavy: runs orchestrator)
- [x] Add SQS FIFO queue + DLQ
- [x] Add API Gateway route: `POST /agent/webhook`
- [x] Add environment variables:
  - `AGENT_ENABLED` (boolean, default: false)
  - `AGENT_TRIGGER_PREFIX` (string, default: "@ai")
  - `AGENT_MAX_TOOL_TURNS` (int, default: 10)
  - `AGENT_MAX_WALL_CLOCK_SECONDS` (int, default: 720 — 12 min circuit breaker)
  - `AGENT_MAX_TOOL_OUTPUT_CHARS` (int, default: 20000)
  - `AGENT_COMMENT_AUTHOR` (string — bot's Jira display name for loop prevention)
  - `AGENT_SQS_QUEUE_URL` (string — FIFO queue URL)
- [x] Add IAM permissions for both Lambdas + SQS

---

### Phase 2: Multi-Turn Conversation State


#### 2.1 Data Model & Persistence `[TDD]`

- [x] Create `ConversationMessage` DynamoDB entity
  - PK: `AGENT#{ticketKey}`
  - SK: `MSG#{timestamp}#{sequence}`
  - Attributes: role, author, contentJson, tokenCount, ttl
- [x] Create `ConversationRepository` interface
- [x] Implement `DynamoConversationRepository`
- [x] Update `ServiceFactory` to provide `ConversationRepository`

#### 2.2 Logic & Orchestration `[TDD]`

- [x] Implement `ConversationWindowManager` logic
  - Token counting & budgeting
  - Pruning strategy (keep recent N + fit budget)
- [x] Update `AgentOrchestrator` to use `ConversationWindowManager`
  - Load history at start of turn
  - Append user message
  - Append assistant response
  - Append tool results
- [x] Update `AgentProcessorHandler` to wire it all together

### Phase 3: Multi-Actor Collaboration + Guardrails

#### 3.1 Guardrails Framework `[DONE]`

Risk-based guardrails that gate HIGH-risk tool calls behind human approval:

- [x] `RiskLevel` enum: LOW (read-only), MEDIUM (write), HIGH (destructive)
- [x] `ActionPolicy` record with `autoExecute()` / `requireApproval()` factories
- [x] `ToolRiskRegistry`: pattern-based tool→risk mapping with contextual assessment
  - DEFAULT rules: HIGH (`_merge_`, `_delete_`), MEDIUM (`_create_branch`, `_commit_files`, `_create_pr`, `_update_status`), LOW (`_get_`, `_search_`, `_list_`, `_add_comment`)
  - Context-sensitive: `_update_status` with status containing "done"/"closed" → HIGH
  - Per-tool exact overrides (highest priority)
- [x] `GuardedToolRegistry`: decorator around `ToolRegistry`
  - LOW/MEDIUM → execute immediately
  - HIGH → store pending approval in DynamoDB, return approval prompt
  - `executeApproved()` for consuming approved actions
- [x] `ApprovalStore`: DynamoDB-backed store (PK=AGENT#{ticketKey}, SK=APPROVAL#{timestamp})
  - 24-hour TTL auto-expiry for unanswered approvals
  - `storePendingApproval()`, `getLatestPending()`, `consumeApproval()`
- [x] `AgentConfig` updated: `guardrailsEnabled`, `costBudgetPerTicket`, `classifierUseLlm`

Files: `core/.../agent/guardrail/{RiskLevel,ActionPolicy,ToolRiskRegistry,GuardedToolRegistry,ApprovalStore}.java`

#### 3.2 Enhanced Intent Classification `[DONE]`

- [x] Expanded `CommentIntentClassifier` with richer pattern matching:
  - REJECTION_KEYWORDS: "reject", "deny", "cancel", "no don't"
  - Expanded FEEDBACK_KEYWORDS: 18 patterns including "instead", "rather", "please change"
  - QUESTION_STARTERS: "what", "how", "why", "where", "when", "is there", "can you explain"
  - Reordered: rejection → approval → feedback → question → AI_COMMAND
- [x] Optional Claude (Haiku) fallback behind `AGENT_CLASSIFIER_USE_LLM=true` flag
  - Only triggered for ambiguous cases; adds ~500ms latency
  - Constructor overload: `CommentIntentClassifier(AiClient, boolean useLlmFallback)`

#### 3.3 Feedback Loop Handler `[DONE]`

- [x] Intent-aware system prompts in `AgentOrchestrator`:
  - `HUMAN_FEEDBACK` → prompt instructs Claude to review conversation history, apply feedback, update artifacts
  - `QUESTION` → prompt instructs Claude to gather info via tools and provide concise answer
  - `APPROVAL` → handled by `AgentProcessorHandler` before reaching orchestrator
  - `AI_COMMAND` (default) → standard guidelines
- [x] `process(AgentRequest, CommentIntent)` overload in `AgentOrchestrator`

**Key insight:** No new handler class needed — the orchestrator + conversation history + intent-aware prompts handle this naturally.

#### 3.4 Approval Handler `[DONE]`

- [x] `AgentProcessorHandler` intercepts APPROVAL intent before entering orchestrator loop:
  1. Loads pending approval from `ApprovalStore.getLatestPending()`
  2. If found + approved → `GuardedToolRegistry.executeApproved()`
  3. If found + rejected → consume approval, post rejection message
  4. If not found → inform user no pending action
- [x] Wired `CommentIntentClassifier` into `AgentProcessorHandler` constructor

#### 3.5 Cost Tracking `[DONE]`

- [x] `CostTracker`: per-ticket token budget enforcement
  - DynamoDB: PK=AGENT#{ticketKey}, SK=COST_SUMMARY, 30-day TTL
  - `hasRemainingBudget()`, `addTokens()` (atomic ADD), `getRemainingBudget()`
  - Default budget: 200k tokens per ticket (configurable via `AGENT_COST_BUDGET_PER_TICKET`)
  - Non-fatal: tracking failures don't block processing
- [x] Budget check at start of `AgentOrchestrator.process()`
- [x] Token cost recorded after each orchestrator completion

---

### Phase 4: MCP Integration Gateway `[DONE]`

**Rationale:** Instead of building custom Java clients for each external service (Datadog, Slack, Databricks), we use the MCP (Model Context Protocol) Java SDK to bridge ANY MCP server into our ToolProvider contract. One `McpBridgeToolProvider` class gives access to hundreds of existing MCP servers with zero custom client code.

#### 4.1 MCP Client Bridge `[DONE]`

New Gradle module: `mcp-bridge/` (dependency: `io.modelcontextprotocol.sdk:mcp`)

- [x] `McpBridgeToolProvider implements ToolProvider`:
  - Wraps `McpSyncClient` from MCP Java SDK
  - `tools/list` → `toolDefinitions()` (discovered at construction, cached)
  - `tools/call` → `execute(ToolCall)` (strips namespace prefix, calls MCP server)
  - Tool names prefixed with namespace: `monitoring_search_logs`
  - Schema conversion: MCP JSON Schema → Claude-compatible JSON Schema (mostly pass-through)
  - Result conversion: MCP TextContent blocks → ToolResult text
- [x] `McpConnectionFactory`:
  - Creates connections from `McpServerConfig`
  - **stdio transport**: launches MCP server as child process (e.g., `npx @datadog/mcp-server`)
  - **HTTP/SSE transport**: connects to remote MCP server URL
  - Secret resolution: merges static env + AWS Secrets Manager credentials
  - `McpConnectionException` for connection/init failures

Files: `mcp-bridge/src/.../mcp/{McpBridgeToolProvider,McpConnectionFactory}.java`

#### 4.2 MCP Server Configuration `[DONE]`

- [x] `McpServerConfig` record in `core`:
  ```java
  public record McpServerConfig(
      String namespace,    // "monitoring", "messaging", "data"
      String transport,    // "stdio" or "http"
      String command,      // stdio: "npx @datadog/mcp-server"
      String[] args,       // stdio: additional arguments
      String url,          // http: "https://mcp.datadog.com/v1"
      Map<String, String> env,  // static environment variables
      String secretArn,    // AWS Secrets Manager ARN for credentials
      boolean enabled      // feature flag
  ) {}
  ```
- [x] `AppConfig.getMcpServersConfig()` → reads `MCP_SERVERS_CONFIG` env var (JSON array)
- [x] CDK stack updated with `MCP_SERVERS_CONFIG` env var for AgentProcessorHandler

Example MCP_SERVERS_CONFIG:
```json
[
  {"namespace":"monitoring","transport":"stdio","command":"npx","args":["@datadog/mcp-server"],"secretArn":"arn:aws:secretsmanager:...","enabled":true},
  {"namespace":"messaging","transport":"http","url":"https://mcp.slack.dev","secretArn":"arn:aws:secretsmanager:...","enabled":true}
]
```

#### 4.3 Dynamic Tool Discovery & Registration `[DONE]`

- [x] `ServiceFactory` additions:
  - `getMcpConnectionFactory()` — singleton factory
  - `getMcpToolProviders()` — parses config, connects to each enabled MCP server, discovers tools, returns list of `McpBridgeToolProvider` (cached for Lambda execution context reuse)
  - `createGuardedToolRegistry(ToolRegistry)` — wraps with guardrails
  - `getApprovalStore()`, `getCostTracker()` — Phase 3 singletons
- [x] `AgentProcessorHandler` updated:
  - Registers MCP providers alongside core providers in `ToolRegistry`
  - Creates `GuardedToolRegistry` wrapper
  - Passes `CostTracker` and intent to `AgentOrchestrator`

**Flow at Lambda cold start:**
```
AgentProcessorHandler.processMessage()
  → toolRegistry.register(SourceControlToolProvider)
  → toolRegistry.register(IssueTrackerToolProvider)
  → toolRegistry.register(CodeContextToolProvider)
  → for each MCP config:
      → McpConnectionFactory.connect(config)
      → mcpClient.listTools()  // discover tools
      → toolRegistry.register(new McpBridgeToolProvider(namespace, mcpClient))
  → GuardedToolRegistry wraps toolRegistry
  → AgentOrchestrator(claudeClient, toolRegistry, guardedToolRegistry, ...)
```

#### 4.4 Adding a New MCP Integration (Checklist)

For any future integration (Datadog, Slack, Notion, PagerDuty, etc.):

```
□ Find or create MCP server for the service (npmjs, GitHub, etc.)
□ Add entry to MCP_SERVERS_CONFIG env var in CDK stack
□ Create secret in AWS Secrets Manager with API keys
□ Deploy — tools are auto-discovered at Lambda cold start
□ Enable via ticket label: tool:{namespace}
□ No Java code changes required
```

---

## System Prompt Design

```
You are an AI development assistant integrated into Jira. You help developers
investigate issues, write code, create PRs, and coordinate with the team.

CONTEXT:
- Ticket: {ticketKey} — {summary}
- Repository: {owner}/{repo} ({platform})
- Default branch: {defaultBranch}
- Requester: {commentAuthor}

RULES:
1. Always explain what you're doing before taking action
2. For destructive operations (merge, delete), ask for explicit confirmation
3. When creating code, include unit tests
4. Use the ticket key as branch name prefix: ai/{ticketKey}-{short-description}
5. Keep Jira comments concise — use code blocks for diffs and logs
6. If you're unsure, ask the developer for clarification

AVAILABLE TOOLS:
{dynamically injected based on ticket config and available integrations}
```

---

## Coexistence with Pipeline Mode

Agent mode does NOT replace the existing Step Functions pipeline. Both modes coexist:

| Trigger | Mode | Use Case |
|---------|------|----------|
| Jira ticket `status → In Progress` | Pipeline | Automated full PR generation |
| Jira comment `@ai ...` + label `ai-agent` | Agent | Interactive investigation & collaboration |

The `JiraWebhookHandler` routes based on event type:
- `jira:issue_updated` (status change) → existing pipeline
- `jira:comment_created` → agent mode (if enabled)

---

## Compute Considerations

**Phase 1-2 (Lambda + FIFO SQS):**
- Each comment = one Lambda invocation via SQS FIFO trigger
- FIFO ordering ensures sequential processing per ticket (no race conditions)
- Circuit breaker at 12 minutes wall-clock: posts partial results, persists state
- If a turn is interrupted, the next comment (or a self-triggered continuation) resumes
- Most single-turn interactions complete in 30-60 seconds
- Multi-tool chains (5-10 tools) typically complete in 2-5 minutes

**Phase 3+ (ECS Fargate — optional):**
- Consider ECS Fargate with spot instances for:
  - Agent sessions that consistently exceed 12 minutes
  - Streaming responses (real-time progress vs poll-based)
  - Long-running background tasks (e.g., full codebase analysis)
- **Not** Step Functions Express: Claude's tool-use loop requires full message history
  per turn; Express Workflows' 256KB payload limit and state serialization overhead
  make it a poor fit for this pattern

**Why not Step Functions Express for the agentic loop:**
The agentic loop is fundamentally a synchronous request-response cycle with Claude.
Each turn requires the full conversation (messages + tool results). Splitting this
across Step Functions states means serializing/deserializing the full array on every
transition, and the 256KB state payload limit becomes a hard blocker once tool results
accumulate. A single Lambda invocation with a wall-clock circuit breaker is simpler
and avoids these constraints.

---

## Testing Strategy

### Phase 1-2 Tests (Complete)

- [x] Unit test `CommentIntentClassifier` with various comment formats
- [x] Unit test `ToolRegistry` — provider registration, namespace routing, ticket-based filtering
- [x] Unit test `SourceControlToolProvider` — mock `SourceControlClient`, verify typed dispatch
- [x] Unit test `IssueTrackerToolProvider` — mock `IssueTrackerClient`, verify typed dispatch
- [x] Unit test `AgentOrchestrator` — mock Claude responses with tool_use, verify agentic loop
- [x] Unit test `JiraCommentFormatter` — markdown conversion, truncation, code block regex
- [x] Unit test namespace extraction — edge cases (unknown namespace, no underscore)

### Phase 3 Tests (Complete)

- [x] Unit test `ToolRiskRegistry` — risk assessment for LOW/MEDIUM/HIGH, contextual overrides
- [x] Unit test `GuardedToolRegistry` — delegation for LOW, approval gate for HIGH, guardrails disabled mode
- [x] Unit test `CostTracker` — budget check, atomic token add, non-fatal error handling
- [x] Unit test `McpServerConfig` — validation, JSON deserialization, transport detection

### Phase 4 Tests (Complete)

- [x] Unit test `McpBridgeToolProvider` — tool discovery, namespace prefixing, execute flow, error handling, sanitization

### Pending Tests

- [ ] Unit test `ConversationWindowManager` — token budget enforcement, summarization
- [ ] Unit test `ApprovalStore` — DynamoDB store/retrieve/consume lifecycle
- [ ] Integration test: full webhook → Claude → tool execution → Jira comment flow
- [ ] Integration test: multi-turn conversation state persistence
- [ ] Integration test: MCP server discovery + registration + tool execution
- [ ] Integration test: approval flow (HIGH risk → approval comment → execution)
- [ ] Load test: concurrent comments on same ticket (FIFO ordering)

---

## Files Created/Modified (All Phases)

### Phase 1-2: Core Agent Framework

| Status | File |
|--------|------|
| ✅ | `core/.../agent/AgentOrchestrator.java` |
| ✅ | `core/.../agent/CommentIntentClassifier.java` |
| ✅ | `core/.../agent/JiraCommentFormatter.java` |
| ✅ | `core/.../agent/ProgressTracker.java` |
| ✅ | `core/.../agent/ConversationRepository.java` |
| ✅ | `core/.../agent/DynamoConversationRepository.java` |
| ✅ | `core/.../agent/ConversationWindowManager.java` |
| ✅ | `core/.../agent/model/{AgentRequest,AgentResponse,CommentIntent,ConversationMessage}.java` |
| ✅ | `core/.../agent/tool/{ToolProvider,ToolRegistry,Tool,ToolCall,ToolResult,Schema}.java` |
| ✅ | `tool-source-control/.../SourceControlToolProvider.java` |
| ✅ | `tool-issue-tracker/.../IssueTrackerToolProvider.java` |
| ✅ | `tool-code-context/.../CodeContextToolProvider.java` |
| ✅ | `spring-boot-app/.../AgentWebhookController.java` |
| ✅ | `spring-boot-app/.../AgentSqsListener.java` |
| ✅ | `spring-boot-app/.../config/AgentConfig.java` (Spring Boot configuration) |
| ✅ | `claude-client/.../SpringAiClientAdapter.java` (modified: tool-use support) |
| ✅ | `core/.../config/{AppConfig,AgentConfig}.java` (modified) |

### Phase 3: Guardrails + Multi-Actor Collaboration

| Status | File |
|--------|------|
| ✅ | `core/.../agent/guardrail/RiskLevel.java` |
| ✅ | `core/.../agent/guardrail/ActionPolicy.java` |
| ✅ | `core/.../agent/guardrail/ToolRiskRegistry.java` |
| ✅ | `core/.../agent/guardrail/GuardedToolRegistry.java` |
| ✅ | `core/.../agent/guardrail/ApprovalStore.java` |
| ✅ | `core/.../agent/CostTracker.java` |
| ✅ | `core/.../config/AgentConfig.java` (modified: 3 new fields) |
| ✅ | `core/.../agent/CommentIntentClassifier.java` (modified: enhanced patterns + LLM fallback) |
| ✅ | `core/.../agent/AgentOrchestrator.java` (modified: intent-aware prompts, guardrails, cost) |
| ✅ | `spring-boot-app/.../AgentSqsListener.java` (intent routing, approval flow) |

### Phase 4: MCP Integration Gateway

| Status | File |
|--------|------|
| ✅ | `mcp-bridge/build.gradle` (new Gradle module) |
| ✅ | `mcp-bridge/.../mcp/McpBridgeToolProvider.java` |
| ✅ | `mcp-bridge/.../mcp/McpConnectionFactory.java` |
| ✅ | `core/.../config/McpServerConfig.java` |
| ✅ | `core/.../config/AppConfig.java` (modified: `getMcpServersConfig()`) |
| ✅ | `spring-boot-app/.../config/AgentConfig.java` (MCP factory methods via Spring Boot) |
| ✅ | `spring-boot-app/.../config/SpringAiConfig.java` (MCP registration via Spring AI) |
| ✅ | `settings.gradle` (modified: `include 'mcp-bridge'`) |
| ✅ | `infrastructure/lib/ai-driven-stack.ts` (modified: new env vars) |

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Infinite loop (AI comments trigger AI) | Filter by comment author — skip if author matches bot account |
| Cost explosion (many tool calls) | Per-turn tool limit (10), per-ticket token budget (200k), CloudWatch alarms |
| Runaway agent (wrong action) | Risk-level classification — HIGH actions require explicit `@ai approve` |
| Context window overflow | Sliding window + summarization in `ConversationWindowManager` |
| Concurrent comments race condition | **SQS FIFO queue** with `MessageGroupId=ticketKey` ensures sequential processing per ticket |
| Claude hallucinates tool names | `ToolRegistry` validates namespace exists before dispatch; `ToolProvider` validates action within namespace |
| Tool output blows up context | `ToolProvider.maxOutputChars()` contract — each provider truncates domain-appropriately (logs: first/last N lines; queries: first 50 rows + schema) |
| Lambda timeout on long chains | **Wall-clock circuit breaker** (12 min): posts partial results, persists state for continuation |
| User thinks AI is broken (silent gap) | **Ack comment** posted immediately; updated in-place as tools complete |
| Jira API rate limits | Exponential backoff + circuit breaker on Jira client |
| SQS message poison pill | Dead-letter queue after 3 failed attempts; alarm on DLQ depth |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Comment-to-response latency (P95) | < 60 seconds |
| Tool execution success rate | > 95% |
| Avg tool calls per conversation turn | 2-4 |
| Developer satisfaction (survey) | > 4/5 |
| Cost per agent conversation | < $2.00 |
| False positive intent classification | < 5% |

---

## MCP Architecture (Implemented)

Phase 4 introduced full MCP integration. The architecture now supports **both directions**:

```
INBOUND (Phase 4 — implemented):
  MCP Server (external) → McpBridgeToolProvider → ToolRegistry → Agent
  - Any MCP server becomes a tool provider via configuration
  - Zero Java code for new integrations (Datadog, Slack, etc.)

OUTBOUND (Phase 6 — planned):
  External Client → MCP Protocol → Our MCP Server → ToolProvider → DomainClient
  - Expose our SourceControl/IssueTracker as MCP servers
  - Other agents/LLMs can call our tools via MCP
```

**Current tool resolution flow:**
```
Comment → Intent Classification → AgentOrchestrator
  → Claude selects tools from:
      SourceControlToolProvider   (native Java — Bitbucket/GitHub)
      IssueTrackerToolProvider    (native Java — Jira)
      CodeContextToolProvider     (native Java — S3 context)
      McpBridgeToolProvider[0..N] (MCP SDK — any external MCP server)
  → GuardedToolRegistry gates HIGH-risk calls
  → CostTracker enforces per-ticket budget
  → Response → Jira comment
```

---

## Future Phases

### Phase 5: Agent Reliability & Observability

Focus: production hardening, debugging, and operational visibility.

#### 5.1 Structured Agent Telemetry

- [ ] Emit structured CloudWatch metrics per agent invocation:
  - `agent.turns` — number of agentic loop iterations
  - `agent.tools_called` — tool names + durations
  - `agent.tokens.input` / `agent.tokens.output` — per-turn token usage
  - `agent.intent` — classified intent distribution
  - `agent.risk_gates` — HIGH-risk approvals requested/approved/expired
  - `agent.cost` — dollar cost per conversation (model pricing × tokens)
- [ ] CloudWatch dashboard: Agent Operations panel (add to existing dashboard)
- [ ] CloudWatch alarms:
  - Budget exceeded (per-ticket cost > threshold)
  - Approval expiry rate > 50% (users ignoring approval prompts)
  - MCP connection failure rate
  - Tool error rate > 10%

#### 5.2 Conversation Replay & Debugging

- [ ] Store full agent trace in DynamoDB/S3:
  - Each turn: system prompt, user message, Claude response, tool calls + results
  - Trace ID = `{ticketKey}/{ackCommentId}`
- [ ] Replay API: given a trace ID, reconstruct the full conversation for debugging
- [ ] Admin CLI or Lambda: `GET /agent/trace/{ticketKey}` → returns full conversation JSON

#### 5.3 Retry & Recovery

- [ ] On Lambda timeout (wall-clock circuit breaker):
  - Persist partial state (which tools completed, pending results)
  - Post Jira comment: "Partial results: {summary}. Reply to continue."
  - Next comment on same ticket resumes from saved checkpoint
- [ ] MCP connection recovery:
  - On `McpSyncClient` connection drop, retry with exponential backoff
  - After 3 failures, mark MCP provider as unhealthy, skip for current invocation
  - Re-attempt on next Lambda cold start
- [ ] Tool execution retry:
  - Transient failures (HTTP 429, 503) → retry with backoff
  - Permanent failures (404, 400) → return error to Claude, let it adapt

#### 5.4 Rate Limiting & Abuse Prevention

- [ ] Per-user rate limit: max N agent invocations per hour per user
- [ ] Per-ticket rate limit: max M agent invocations per day per ticket
- [ ] Global circuit breaker: if total agent cost exceeds daily budget, pause all agents
- [ ] DynamoDB-backed rate limiter (atomic counters with TTL)

---

### Phase 6: MCP Server Mode (Bidirectional)

Focus: expose our tools as MCP servers so external agents and LLMs can use them.

#### 6.1 MCP Server for Source Control

- [ ] Create `source-control-mcp-server` (Java or Node):
  - Wraps `SourceControlToolProvider` as an MCP server
  - Exposes tools: `create_branch`, `commit_files`, `create_pr`, `merge_pr`, `get_file`, `search_files`
  - stdio transport for local development, HTTP/SSE for remote
- [ ] Benefits: other teams' agents can create PRs in our repos via MCP

#### 6.2 MCP Server for Issue Tracker

- [ ] Create `issue-tracker-mcp-server`:
  - Wraps `IssueTrackerToolProvider`
  - Exposes tools: `get_ticket`, `add_comment`, `update_status`, `search_issues`
- [ ] Benefits: external CI/CD agents can update Jira tickets via MCP

#### 6.3 MCP Server Hosting (Lambda or ECS)

- [ ] Option A: Lambda Function URL with MCP HTTP transport
  - Stateless, auto-scaling, pay-per-use
  - Each MCP server = one Lambda behind API Gateway
- [ ] Option B: ECS Fargate with stdio/SSE support
  - Long-lived connections, streaming support
  - Better for high-frequency MCP calls
- [ ] CDK construct: reusable `McpServerConstruct` that provisions Lambda + API Gateway + IAM

#### 6.4 MCP Resource Exposure

- [ ] Expose MCP resources (read-only data) alongside tools:
  - `source_control://repo/{owner}/{repo}/file/{path}` → file content
  - `issue_tracker://ticket/{key}` → ticket summary + status
  - `code_context://repo/{owner}/{repo}/tree` → file tree
- [ ] Resources enable Claude Desktop or other MCP clients to browse project context

---

### Phase 7: Advanced Agent Capabilities

Focus: make the agent smarter, more autonomous, and more capable.

#### 7.1 Multi-Step Plans (Chain of Thought)

- [ ] Before executing, have Claude output a numbered plan:
  ```
  Plan:
  1. Search for files referencing PaymentService
  2. Get the file content of PaymentService.java
  3. Create branch ai/ONC-10001-fix-npe
  4. Commit fix with null safety + unit test
  5. Open PR with description
  ```
- [ ] Post plan as Jira comment, wait for "proceed" or "modify step 3..."
- [ ] Track plan execution progress (steps completed, remaining)

#### 7.2 Background Monitoring Agent

- [ ] Scheduled agent that runs periodically (EventBridge cron → SQS):
  - Check Datadog for new alerts → create/update Jira tickets
  - Check PRs for stale reviews → ping reviewers via Jira comment
  - Check build status → post CI results as Jira comments
- [ ] Requires: MCP servers for monitoring (Phase 4 config)
- [ ] Trigger: EventBridge rule → SQS → AgentProcessorHandler with synthetic `AgentRequest`

#### 7.3 Agent-to-Agent Collaboration

- [ ] Agent A (Jira agent) can invoke Agent B (code review agent) via MCP:
  - Agent A creates PR → triggers Agent B to review
  - Agent B posts review comments → Agent A processes feedback
- [ ] Protocol: Agent A calls `code_review_request` MCP tool → Agent B's MCP server

#### 7.4 Streaming Responses

- [ ] Replace poll-based progress with streaming:
  - WebSocket connection from Jira plugin to agent
  - Claude's streaming API → real-time tool execution updates
  - Requires ECS Fargate (Lambda doesn't support WebSocket well)
- [ ] Alternative: Progressive Jira comment edits (edit every 5 seconds during execution)

#### 7.5 Context-Aware Tool Selection

- [ ] Auto-enable tools based on ticket context (no manual labels):
  - Ticket mentions "logs" or "error" → enable monitoring tools
  - Ticket has PR linked → enable source control tools
  - Ticket references Databricks → enable data tools
- [ ] Claude-based: include ticket context in tool selection prompt
- [ ] Rule-based fallback: keyword → namespace mapping

#### 7.6 Knowledge Base Integration

- [ ] MCP server for Confluence/Notion:
  - `knowledge_search_docs` — search internal docs
  - `knowledge_get_page` — get page content
- [ ] Agent can reference runbooks, architecture docs, coding standards
- [ ] Improves code quality by grounding in team conventions

---

## Implementation Priority Matrix

| Phase | Effort | Value | Risk | Status |
|-------|--------|-------|------|--------|
| Phase 1: Single-Turn Agent | M | High | Low | ✅ Done |
| Phase 2: Multi-Turn State | M | High | Low | ✅ Done |
| Phase 3: Guardrails + Multi-Actor | M | High | Med | ✅ Done |
| Phase 4: MCP Gateway | M | Very High | Med | ✅ Done |
| Phase 5: Observability | S | High | Low | 🔜 Next |
| Phase 6: MCP Server Mode | L | Med | Med | 📋 Planned |
| Phase 7: Advanced Capabilities | XL | High | High | 📋 Planned |

**Recommended next:** Phase 5 (operational visibility before scaling usage), then Phase 7.1-7.2 (plan mode + background monitoring are highest user value).
