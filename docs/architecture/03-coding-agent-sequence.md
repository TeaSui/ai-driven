# Coding Agent — Request Flow Sequence

Step-by-step flow from user click to real-time streaming response, including tool calls, prompt caching, and evaluation.

```mermaid
sequenceDiagram
    autonumber
    participant U as Web UI
    participant API as FastAPI
    participant PG as PostgreSQL
    participant SQS as SQS Queue
    participant Agent as Coding Agent
    participant Claude as Claude API
    participant Tools as Tool Registry
    participant GH as GitHub API
    participant Redis as Redis Pub/Sub
    participant WS as WebSocket
    participant Eval as Evaluation

    U->>API: POST /api/chat/send
    Note over API: {message, agentType: "coding", context: {repo, jiraKey}}
    API->>PG: INSERT task (status: queued)
    API-->>U: {taskId, status: "queued"}

    U->>WS: Connect WS /stream/{taskId}
    WS->>Redis: SUBSCRIBE task:{taskId}:events
    WS-->>U: {type: "connected"}

    alt Debug Mode
        API->>Agent: Spawn async inline
    else Production
        API->>SQS: Enqueue task
        SQS->>Agent: Worker pulls task
    end

    Agent->>PG: UPDATE status = "running"
    Agent->>Redis: PUBLISH phase_change (started)
    Redis-->>WS: Forward event
    WS-->>U: phase_change

    opt RAG Auto-Index (repo > 500 files)
        Agent->>PG: Check code_embeddings for repo
        alt Not yet indexed
            Agent->>Redis: PUBLISH rag_indexing_start
            Redis-->>WS: Forward
            WS-->>U: Show indexing progress
            Agent->>PG: INSERT code_embeddings (chunked + embedded)
            Agent->>Redis: PUBLISH rag_indexing_complete
            Redis-->>WS: Forward
            WS-->>U: RAG ready
            Note over Agent: Register semantic_search_code tool
        else Already indexed
            Note over Agent: Register semantic_search_code tool (reuse existing index)
        end
        Agent->>Redis: SET repo_context:{full_name} (shared with ReviewAgent)
    end

    Agent->>Redis: PUBLISH prompt_sent
    Redis-->>WS: Forward
    WS-->>U: Show system prompt

    loop Agent Loop (max 50 iterations, halts at $3.00 cost ceiling)
        Agent->>Redis: GET task:{taskId}:control
        alt Control = "stopped"
            Agent->>Redis: PUBLISH complete (stopped by user)
            Note over Agent: Break loop
        else Control = "paused"
            Agent->>Redis: PUBLISH phase_change (paused)
            loop Wait for resume
                Agent->>Redis: GET task:{taskId}:control
            end
            Agent->>Redis: PUBLISH phase_change (resumed)
        end
        Agent->>Claude: messages + tools + system
        Note over Claude: Prompt caching: system + tools + messages

        Claude-->>Agent: Response (tool_use or text)

        alt Tool Call
            Agent->>Redis: PUBLISH tool_call_start
            Redis-->>WS: Forward
            WS-->>U: Show tool invocation

            Agent->>Tools: Execute tool
            Tools->>GH: API call (read_file, create_pr, etc.)
            GH-->>Tools: Result
            Tools-->>Agent: Tool result

            Agent->>Redis: PUBLISH tool_call_end
            Redis-->>WS: Forward
            WS-->>U: Show tool result

            Note over Agent: Append tool_result to messages, continue loop
        else Streaming Text
            loop Buffered chunks (200ms / 500 chars)
                Agent->>Redis: PUBLISH streaming_chunk
                Redis-->>WS: Forward
                WS-->>U: Append text in real-time
            end
        else Final Response (no tool_use)
            Agent->>Redis: PUBLISH text_delta (complete response)
            Redis-->>WS: Forward
            WS-->>U: Replace streaming text with final
            Note over Agent: Break loop
        end

        Agent->>Redis: PUBLISH iteration (tokens, cache hits, cost)
        Redis-->>WS: Forward
        WS-->>U: Update metrics badge
    end

    Agent->>PG: UPDATE status = "completed", output_data
    Agent->>Redis: PUBLISH complete
    Redis-->>WS: Forward
    WS-->>U: Show completion + PR link

    Agent--)Eval: Fire-and-forget: evaluate task
    Eval->>Claude: Grade each dimension (Haiku)
    Eval->>PG: INSERT task_evaluations
```

## Event Types Published

| Event | When | Data |
|-------|------|------|
| `phase_change` | Agent starts | `{phase: "started"}` |
| `prompt_sent` | Before first Claude call | `{system: "...", user: "..."}` |
| `tool_call_start` | Tool invoked | `{name, input}` |
| `tool_call_end` | Tool finished | `{name, duration_ms, success, result}` |
| `streaming_chunk` | Every 200ms or 500 chars (buffered text deltas) | `{content, iteration}` |
| `response_chunk` | After each Claude response text | `{content, iteration}` |
| `rag_indexing_start` | RAG auto-index begins | `{repo, estimated_files}` |
| `rag_indexing_complete` | RAG auto-index finished | `{repo, chunks}` |
| `iteration` | After each Claude response | `{iteration, input_tokens, output_tokens, total_input_tokens, total_output_tokens, has_tool_use, stop_reason, cache_read_tokens, cache_creation_tokens}` |
| `text_delta` | Final response text | `{text}` |
| `complete` | Task done | `{success, iterations, total_tokens, cost}` |
| `error` | Task failed | `{message}` |
