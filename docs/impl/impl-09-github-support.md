# impl-09: GitHub Support (3.1)

**Status**: ✅ Done
**Priority:** 3.1
**Impact:** Extend platform beyond Bitbucket to support GitHub-hosted repositories

---

## Goal

Add GitHub as an alternative source control platform:
- New `github-client` module (mirrors `bitbucket-client`)
- Shared `SourceControlClient` interface
- Auto-detect platform from Jira ticket metadata

## Proposed Changes

### SourceControlClient Interface
- [x] Create interface in `core` module:
  ```java
  public interface SourceControlClient {
      byte[] downloadRepository(String owner, String repo, String branch);
      PullRequestResult createPullRequest(PrCreateRequest request);
      List<String> getFileTree(String owner, String repo, String branch);
  }
  ```
- [x] Refactor `BitbucketClient` to implement `SourceControlClient`

### GitHub Client Module
- [x] Create `github-client` Gradle submodule
- [x] Implement `GitHubClient implements SourceControlClient`
- [x] Support GitHub REST API v3 for:
  - [x] Repository archive download
  - [x] Pull request creation
  - [x] File tree listing
- [x] Add `github-client` to `lambda-handlers` dependencies

### Secrets & Auth
- [x] Add `GITHUB_SECRET_ARN` to CDK stack
- [x] Create `ai-driven/github-credentials` secret in Secrets Manager
- [x] Support GitHub App or Personal Access Token auth

### Platform Detection
- [x] Create `PlatformResolver` utility
- [x] Detect platform from:
  - Jira label: `platform:github` or `platform:bitbucket`
  - Custom Jira field: `Repository URL` (parse `github.com` vs `bitbucket.org`)
- [x] Pass `platform` through Step Functions state

### Handler Updates
- [x] `BitbucketFetchHandler` → use resolved platform to select client (rename or create `CodeFetchHandler`)
- [x] `PrCreatorHandler` → use `SourceControlClient` interface for PR creation
- [x] Both handlers select the right client based on `platform` input

## Testing Strategy

- [ ] Unit test `GitHubClient` with mock HTTP responses
- [x] Unit test `PlatformResolver` with various Jira ticket formats
- [ ] Integration test: E2E workflow with GitHub repo

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/source/SourceControlClient.java` |
| NEW | `github-client/` (new Gradle submodule) |
| NEW | `github-client/src/main/java/com/aidriven/github/GitHubClient.java` |
| NEW | `core/src/main/java/com/aidriven/core/util/PlatformResolver.java` |
| MODIFY | `bitbucket-client/src/main/java/com/aidriven/bitbucket/BitbucketClient.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/BitbucketFetchHandler.java` |
| MODIFY | `lambda-handlers/src/main/java/com/aidriven/lambda/PrCreatorHandler.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
