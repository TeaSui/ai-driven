# impl-18: IDE Context AST (Hybrid)

## Status: ✅ COMPLETED

## Context & Motivation

The AI agent relies on text-based file reads (`get_file`) to understand the codebase. For large Java files, this fetches thousands of lines of method bodies — the vast majority of which Claude does not need to understand the *structure* of code. This causes:

- **Token bloat**: Body logic is ~80% of a Java file, yet Claude only needs class/method signatures to plan changes.
- **Latency**: Especially in multi-turn ReAct loops calling `get_file` repeatedly on the same file.
- **No text-search**: Claude had no way to grep for specific patterns (e.g., "find all usages of `UserService`").

## Solution

Introduce an **AST-based outline tool** that extracts a structural skeleton of any file, and a complementary **grep tool** for pattern search. For Java files, `javaparser` gives high-fidelity symbolic extraction. For all other file types, a safe truncation fallback returns the first 2000 characters.

---

## Implementation

### 1. AST Domain — `application/core`

| File | Description |
|---|---|
| `core/src/main/java/com/aidriven/core/ast/CodeNode.java` | Lombok `@Data @Builder` value object. Fields: `name`, `type`, `signature`, `startLine`, `endLine`, `children`. |
| `core/src/main/java/com/aidriven/core/ast/AstParser.java` | Interface: `List<CodeNode> parse(String filename, String content, int maxDepth)` |
| `core/src/main/java/com/aidriven/core/ast/java/JavaAstParser.java` | Java implementation using `javaparser-core 3.25.8`. Extracts top-level classes and methods. Falls back to RAW_TEXT on non-`.java` files or parse errors. |

**`maxDepth` semantics:**
- `0` → class names only
- `1` → class + method signatures (default)
- `2` → class + methods + fields

**Fallback:** Non-Java files → first 2,000 characters wrapped as a single `RAW_TEXT` node.

### 2. Dependencies — `core/build.gradle` & `tool-source-control/build.gradle`

```groovy
// core/build.gradle
api 'com.github.javaparser:javaparser-core:3.25.8'
api 'com.github.ben-manes.caffeine:caffeine:3.1.8'

// tool-source-control/build.gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

### 3. New Agent Tools — `SourceControlToolProvider`

#### `source_control_view_file_outline`
Fetches a file and returns its AST skeleton as Markdown. Claude should prefer this over `get_file` for large Java files.

| Input | Type | Description |
|---|---|---|
| `file_path` | string (required) | Path to the file |
| `branch` | string (optional) | Defaults to repo default branch |
| `max_depth` | int (optional) | 0=class, 1=class+methods (default), 2=+fields |

- Results are cached for 10 minutes (Caffeine, max 200 entries) to prevent re-parsing across ReAct turns.

#### `source_control_search_grep`
Grep-style search for literal or regex patterns within a file or across search results.

| Input | Type | Description |
|---|---|---|
| `query` | string (required) | Literal string or regex pattern |
| `file_path` | string (optional) | Restrict search to this file |
| `branch` | string (optional) | Defaults to repo default branch |
| `is_regex` | string (optional) | Set to `"true"` for regex mode |

- Returns `file:line: content` references.
- Capped at **50 matches** to protect context window.
- Searches up to **10 files** from `search_files` results when `file_path` is omitted.

---

## Tests

| Test File | Coverage |
|---|---|
| `JavaAstParserTest` | Class extraction, method signatures, `maxDepth=0`, non-Java fallback |
| `SourceControlToolProviderTest` | Tool count (10 tools), outline success, non-Java fallback, file-not-found, grep match, grep no-matches |

---

## Design Decisions

- **Java-first for MVP**: `javaparser` parses Java with high fidelity and no server process. Languages like Python/JS/TS fall back to raw truncation, sufficient for the current Java-centric codebase.
- **Caching in provider**: Caffeine TTL cache in `SourceControlToolProvider` avoids redundant S3/API calls in multi-turn agent loops.
- **No Lambda cold-start impact**: `javaparser` is a pure Java library; parsing is in-memory and typically completes in <100ms for files under 10k lines.
- **Extendable**: `AstParser` interface makes adding a `PythonAstParser` or Tree-sitter adapter straightforward later.
