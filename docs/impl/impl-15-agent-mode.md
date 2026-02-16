# impl-15: Conversational Agent Mode (5.1)

**Status:** In Progress
**Priority:** 5.1
**Impact:** Transform from deterministic pipeline to interactive AI agent — Jira comments become a chat interface for multi-turn, multi-tool orchestration

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
│              AgentWebhookHandler (Lambda)                     │
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
│          ToolRegistry (routes by namespace)                   │
│                                                              │
│  ┌─ SourceControlToolProvider ─┐ ┌─ IssueTrackerToolProvider ┐│
│  │  namespace: source_control  │ │  namespace: issue_tracker  ││
│  │  wraps: SourceControlClient │ │  wraps: IssueTrackerClient ││
│  │  • create_branch            │ │  • get_ticket              ││
│  │  • commit_files             │ │  • add_comment             ││
│  │  • create_pr                │ │  • transition_ticket       ││
│  │  • get_file / search_files  │ │  • update_status           ││
│  └─────────────────────────────┘ └────────────────────────────┘│
│                                                              │
│  ┌─ MonitoringToolProvider ────┐ ┌─ MessagingToolProvider ───┐│
│  │  namespace: monitoring      │ │  namespace: messaging      ││
│  │  wraps: MonitoringClient    │ │  wraps: MessagingClient    ││
│  │  • search_logs              │ │  • send_message            ││
│  │  • get_metrics              │ │  • send_channel_message    ││
│  │  • get_alerts               │ │  • create_thread           ││
│  └─────── (Phase 4) ──────────┘ └──────── (Phase 4) ────────┘│
│                                                              │
│  ┌─ DataToolProvider ─────────┐ ┌─ KnowledgeToolProvider ───┐│
│  │  namespace: data            │ │  namespace: knowledge      ││
│  │  wraps: DataClient          │ │  wraps: KnowledgeClient    ││
│  │  • execute_query            │ │  • search_docs             ││
│  │  • list_tables              │ │  • get_page                ││
│  │  • describe_table           │ │                            ││
│  └─────── (Phase 4) ──────────┘ └──────── (Future) ─────────┘│
└──────────────────────────────────────────────────────────────┘
```

### Modular Monolith Architecture

The agent is built as a **Modular Monolith** to ensure flexibility and separation of concerns without the operational overhead of microservices.

*   **`core` Module**: Contains the `AgentOrchestrator`, `ToolRegistry`, `AiClient`, and `ToolProvider` interface.
*   **`tool-source-control` Module**: Contains `SourceControlToolProvider` and depends on `core`.
*   **`tool-issue-tracker` Module**: Contains `IssueTrackerToolProvider` and depends on `core`.
*   **`lambda-handlers`**: Depends on `core` and specific `tool-*` modules to assemble the agent.

### Trade-offs (Counter-arguments)

While the Modular Monolith structure provides good separation of concerns, it does have some trade-offs compared to a microservices or plugin-based architecture:

1.  **Deployment Coupling**: All tools (`source-control`, `issue-tracker`, etc.) are bundled into a single Lambda function (`AgentWebhookHandler`). A change in one module requires redeploying the entire agent.
2.  **Build-Time Composition**: Tools are wired together at build time in `lambda-handlers`. Adding a new tool requires code changes to `ServiceFactory` and a recompile; it is not a dynamic runtime plugin system.
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

      private final ClaudeClient claudeClient;
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

- [x] Extend `ClaudeClient` to support tool-use API:
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
- [ ] Flow:
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

#### 3.1 Actor-Aware Intent Classification

- [ ] Upgrade `CommentIntentClassifier` to use Claude for ambiguous cases:
  - Rule-based fast path for obvious intents (`@ai`, `LGTM`, etc.)
  - Claude classification fallback for ambiguous comments
  - Context-aware: if AI posted a PR and next comment is from reviewer → likely feedback

#### 3.2 Feedback Loop Handler

- [ ] Handle reviewer feedback on AI-created PRs:
  ```
  Reviewer: "Use Optional instead of null check"
  → AI loads conversation context (knows which PR, which files)
  → AI modifies files, pushes new commit to same PR
  → AI comments: "Updated based on feedback. Changes: [diff summary]"
  ```

#### 3.3 Approval Handler

- [ ] Handle approval signals:
  ```
  Reviewer: "LGTM, merge it"
  → AI detects APPROVAL intent
  → AI comments: "Merging PR #342..." (or asks for confirmation if configured)
  → AI merges PR via source control API
  → AI transitions Jira ticket to "Done"
  ```

#### 3.4 Guardrails

- [ ] Configurable approval requirements for high-risk actions:
  ```java
  public enum RiskLevel { LOW, MEDIUM, HIGH }

  // LOW: search logs, read files, add comments → auto-execute
  // MEDIUM: create branch, commit code, open PR → execute, notify
  // HIGH: merge PR, deploy, delete branch → require explicit approval comment
  ```
- [ ] Tool call budget per conversation turn (default: 10)
- [ ] Token budget per ticket conversation (default: 200k total)
- [ ] Cost tracking per ticket in DynamoDB metrics

---

### Phase 4: External Tool Integrations (Future)

Each new integration follows the same pattern: **domain interface → client impl → ToolProvider → register**.

```
Step 1: Define typed domain interface in `core`
Step 2: Implement client in new Gradle module
Step 3: Create ToolProvider wrapping the client
Step 4: Register in ServiceFactory.getToolRegistry()
```

Zero changes to AgentOrchestrator, ToolRegistry, or existing providers.

#### 4.1 Monitoring — Datadog Integration

- [ ] Create `MonitoringClient` interface in `core` module:
  ```java
  public interface MonitoringClient {
      List<LogEntry> searchLogs(LogQuery query) throws Exception;
      List<MetricPoint> getMetrics(MetricQuery query) throws Exception;
      List<Alert> getActiveAlerts(String service) throws Exception;
  }
  ```
- [ ] Create `datadog-client` Gradle module with `DatadogClient implements MonitoringClient`
- [ ] Create `MonitoringToolProvider implements ToolProvider`:
  ```java
  public class MonitoringToolProvider implements ToolProvider {
      private final MonitoringClient client;

      @Override public String namespace() { return "monitoring"; }

      @Override
      public List<Tool> toolDefinitions() {
          return List.of(
              Tool.of("monitoring_search_logs", "Search application logs by query, service, and time range", ...),
              Tool.of("monitoring_get_metrics", "Query time-series metrics for a service", ...),
              Tool.of("monitoring_get_alerts", "Get active alerts/monitors for a service", ...)
          );
      }

      @Override
      public ToolResult execute(ToolCall call) {
          return switch (call.name()) {
              case "monitoring_search_logs" -> {
                  LogQuery query = mapper.convertValue(call.input(), LogQuery.class);
                  yield ToolResult.success(client.searchLogs(query));
              }
              // ... other tools
          };
      }
  }
  ```
- [ ] Register: `registry.register(new MonitoringToolProvider(getDatadogClient()))`
- [ ] Enable via ticket label: `tool:monitoring`

#### 4.2 Messaging — Slack Integration

- [ ] Create `MessagingClient` interface in `core` module:
  ```java
  public interface MessagingClient {
      MessageResult sendDirectMessage(String userId, String message) throws Exception;
      MessageResult sendChannelMessage(String channel, String message) throws Exception;
      MessageResult createThread(String channel, String parentTs, String message) throws Exception;
  }
  ```
- [ ] Create `slack-client` Gradle module with `SlackClient implements MessagingClient`
- [ ] Create `MessagingToolProvider implements ToolProvider` (namespace: `messaging`)
- [ ] Register: `registry.register(new MessagingToolProvider(getSlackClient()))`
- [ ] Enable via ticket label: `tool:messaging`

#### 4.3 Data — Databricks Integration

- [ ] Create `DataClient` interface in `core` module:
  ```java
  public interface DataClient {
      QueryResult executeQuery(String sql, String warehouse) throws Exception;
      List<TableInfo> listTables(String catalog, String schema) throws Exception;
      TableSchema describeTable(String catalog, String schema, String table) throws Exception;
  }
  ```
- [ ] Create `databricks-client` Gradle module with `DatabricksClient implements DataClient`
- [ ] Create `DataToolProvider implements ToolProvider` (namespace: `data`)
- [ ] Register: `registry.register(new DataToolProvider(getDatabricksClient()))`
- [ ] Enable via ticket label: `tool:data`

#### 4.4 Adding a New Integration (Checklist)

For any future integration (e.g., Notion, PagerDuty, Confluence):

```
□ Define domain interface in core/src/.../core/{domain}/{DomainClient}.java
□ Create Gradle module: {name}-client/
□ Implement {Name}Client implements {DomainClient}
□ Create {Name}ToolProvider implements ToolProvider
□ Add to ServiceFactory.getToolRegistry()
□ Add secret ARN to CDK stack (if needed)
□ Document label: tool:{namespace}
□ Unit test provider with mocked domain client
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

- [x] Unit test `CommentIntentClassifier` with various comment formats
- [x] Unit test `ToolRegistry` — provider registration, namespace routing, ticket-based filtering
- [x] Unit test `SourceControlToolProvider` — mock `SourceControlClient`, verify typed dispatch
- [x] Unit test `IssueTrackerToolProvider` — mock `IssueTrackerClient`, verify typed dispatch
- [x] Unit test `AgentOrchestrator` — mock Claude responses with tool_use, verify agentic loop
- [ ] Unit test `ConversationWindowManager` — token budget enforcement, summarization
- [x] Unit test `JiraCommentFormatter` — markdown conversion, truncation
- [x] Unit test namespace extraction — edge cases (unknown namespace, no underscore)
- [ ] Integration test: full webhook → Claude → tool execution → Jira comment flow
- [ ] Integration test: multi-turn conversation state persistence
- [ ] Integration test: register multiple providers, verify correct routing
- [ ] Load test: concurrent comments on same ticket (optimistic locking)

---

## Files to Create/Modify

### Core Agent Framework

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/agent/AgentOrchestrator.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/CommentIntentClassifier.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/JiraCommentFormatter.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/ConversationRepository.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/ConversationWindowManager.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/model/AgentRequest.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/model/AgentResponse.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/model/CommentIntent.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/model/ConversationMessage.java` |

### Tool Provider Pattern

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/ToolProvider.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/ToolRegistry.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/Tool.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/ToolCall.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/ToolResult.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/Schema.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/SourceControlToolProvider.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/IssueTrackerToolProvider.java` |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/CodeContextToolProvider.java` |

### Future Tool Providers (Phase 4)

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/monitoring/MonitoringClient.java` |
| NEW | `datadog-client/` (new Gradle module) |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/MonitoringToolProvider.java` |
| NEW | `core/src/main/java/com/aidriven/core/messaging/MessagingClient.java` |
| NEW | `slack-client/` (new Gradle module) |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/MessagingToolProvider.java` |
| NEW | `core/src/main/java/com/aidriven/core/data/DataClient.java` |
| NEW | `databricks-client/` (new Gradle module) |
| NEW | `core/src/main/java/com/aidriven/core/agent/tool/DataToolProvider.java` |

### Existing Files Modified

| Action | File |
|--------|------|
| MODIFY | `claude-client/src/main/java/com/aidriven/claude/ClaudeClient.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/factory/ServiceFactory.java` |
| NEW | `lambda-handlers/src/main/java/com/aidriven/lambda/AgentWebhookHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |

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

## MCP Migration Path (Optional)

Phase 1-3 use Claude's native tool-use API (simpler, fewer moving parts). The `ToolProvider`
pattern is intentionally designed as a 1:1 mapping to future MCP tool servers.

```
Current:
  Claude API → ToolRegistry → ToolProvider.execute() → DomainClient

MCP (future):
  Any LLM → MCP Server (wraps ToolProvider) → DomainClient

  Each ToolProvider becomes an MCP server:
    SourceControlToolProvider  →  source-control-mcp-server
    MonitoringToolProvider     →  monitoring-mcp-server
    MessagingToolProvider      →  messaging-mcp-server
```

Migration is mechanical — the `ToolProvider` interface already mirrors MCP's contract:
- `namespace()` → MCP server name
- `toolDefinitions()` → MCP tool schemas
- `execute(ToolCall)` → MCP tool handler

When to migrate: when external teams need to contribute tools, or when you need to support
non-Claude LLM providers that speak MCP natively.
