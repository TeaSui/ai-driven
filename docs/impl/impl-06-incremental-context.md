# impl-06: Incremental Context (2.1)

**Status:** ✅ Complete  
**Priority:** 2.1  
**Impact:** ~80% reduction in Claude token usage by fetching only relevant files

---

## Goal

Replace the current full-repo download with targeted context fetching:
1. Extract keywords from the Jira ticket
2. Search for relevant files via Bitbucket API (file search + content search)
3. Fetch file tree for structural context
4. Fall back to full-repo download for ambiguous tickets

## Proposed Changes

### Keyword Extraction
- [x] Create `KeywordExtractor` utility in `core` module
- [x] Extract class names, method names, file paths from ticket summary + description
- [x] Support both camelCase and snake_case splitting

### Bitbucket Search Integration
- [x] Add `searchCode(workspace, repo, query)` to `BitbucketClient`
- [x] Add `getFileTree(workspace, repo, branch)` to `BitbucketClient`
- [x] Add `getFileContent(workspace, repo, path, branch)` to `BitbucketClient`

### Smart Context Builder
- [x] Create `IncrementalContextBuilder` in `core` module
- [x] Combine file tree + search results into minimal context
- [x] Score and rank files by relevance
- [x] Include import chains (if file A references file B, include B)

### BitbucketFetchHandler Refactor
- [x] Add `CONTEXT_MODE` env var (`FULL_REPO` | `INCREMENTAL`, default: `INCREMENTAL`)
- [x] Route to incremental path when mode is `INCREMENTAL`
- [x] Keep full-repo as fallback when search yields too few results
- [x] Track files fetched + tokens saved for observability

### CDK Stack
- [x] Add `CONTEXT_MODE` to `lambdaEnvironment`

## Testing Strategy

- [x] Unit test `KeywordExtractor` with various ticket formats
- [x] Unit test `IncrementalContextBuilder` with mock search results
- [x] Integration test: compare incremental vs full-repo context quality

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/util/KeywordExtractor.java` |
| NEW | `core/src/main/java/com/aidriven/core/context/IncrementalContextBuilder.java` |
| MODIFY | `bitbucket-client/src/main/java/com/aidriven/bitbucket/BitbucketClient.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/BitbucketFetchHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
