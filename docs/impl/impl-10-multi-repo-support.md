# impl-10: Multi-Repository Support (3.2)

> **Status**: ✅ Done
> **Priority**: High
> **Owner**: AI-Driven Team
**Impact:** Support multiple target repos instead of one hardcoded workspace/repo pair

---

## Goal

Resolve target repository dynamically from Jira ticket metadata instead of using a single hardcoded workspace/repo.

## Current State

Repository info is currently hardcoded or passed as a single config value. Every ticket targets the same repo.

## Proposed Changes

### Repository Resolution
- [x] Create `RepositoryResolver` utility in `core` module
- [x] Support multiple resolution strategies:
  | Priority | Strategy | Example |
  |----------|----------|---------|
  | 1 | Jira custom field | `Repository URL: https://bitbucket.org/ws/repo` |
  | 2 | Jira label | `repo:my-service` |
  | 3 | Default env var | `DEFAULT_REPO=workspace/repo` |
- [x] Parse full URL to extract workspace + repo slug

### Configuration
- [x] Add `DEFAULT_WORKSPACE` and `DEFAULT_REPO` env vars
- [x] Allow ticket-level override via labels or custom fields

### Handler Updates
- [x] `FetchTicketHandler` — resolve and include `workspace`, `repoSlug` in output
- [x] Wire `BitbucketFetchHandler` to use resolved repo
- [x] Wire `PrCreatorHandler` to use resolved repo
- [x] Test end-to-end flow with non-default repon

### CDK Stack
- [x] Add `DEFAULT_WORKSPACE` and `DEFAULT_REPO` to `lambdaEnvironment`

## Testing Strategy

- [x] Unit test `RepositoryResolver` with all resolution strategies
- [x] Unit test fallback to default when no ticket metadata matches
- [ ] Integration test: ticket with `repo:` label targets correct repo

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/util/RepositoryResolver.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/FetchTicketHandler.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/BitbucketFetchHandler.java` |
| MODIFY | `spring-boot-app/src/main/java/com/aidriven/app/PrCreatorHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
