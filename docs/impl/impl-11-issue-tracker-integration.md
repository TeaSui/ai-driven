# impl-11: Linear / Notion / Shortcut Integration (3.3)

**Status:** 🔶 Partially Done (Interface & Jira implementation complete)  
**Priority:** 3.3  
**Impact:** Support issue trackers beyond Jira

---

## Goal

Abstract the issue tracker integration so multiple platforms can trigger the AI workflow:
- `IssueTrackerClient` interface
- `JiraClient`, `LinearClient`, `NotionClient` implementations
- Webhook adapters per platform

## Proposed Changes

### IssueTrackerClient Interface
- [x] Create interface in `core` module:
  ```java
  public interface IssueTrackerClient {
      TicketInfo getTicket(String ticketKey);
      void addComment(String ticketKey, String comment);
      void transitionTicket(String ticketKey, String transitionId); // Not fully implemented in current PR cycle but jiraClient supports it
      void updateStatus(String ticketKey, String status);
  }
  ```
- [x] Refactor `JiraClient` to implement `IssueTrackerClient`

### Linear Client
- [ ] Create `linear-client` Gradle submodule
- [ ] Implement Linear GraphQL API integration
- [ ] Webhook handler for Linear issue events

### Notion Client
- [ ] Create `notion-client` Gradle submodule
- [ ] Implement Notion API integration (database items as tickets)
- [ ] Webhook handler for Notion page events

### Platform-Agnostic Webhook Handler
- [ ] Create `WebhookRouter` that detects source platform from headers/payload
- [ ] Route to platform-specific parser
- [ ] Normalize to common `TicketInfo` model

### CDK Stack
- [ ] Add API Gateway endpoints: `/linear-webhook`, `/notion-webhook`
- [ ] Add secrets: `ai-driven/linear-credentials`, `ai-driven/notion-credentials`
- [ ] Add `ISSUE_TRACKER_TYPE` env var

## Testing Strategy

- [ ] Unit test each client with mock API responses
- [ ] Unit test `WebhookRouter` with sample payloads from each platform
- [ ] Integration test: Linear webhook triggers full workflow

## Files to Create/Modify

| Action | File |
|--------|------|
| NEW | `core/src/main/java/com/aidriven/core/tracker/IssueTrackerClient.java` |
| NEW | `linear-client/` (new Gradle submodule) |
| NEW | `notion-client/` (new Gradle submodule) |
| NEW | `spring-boot-app/src/main/java/com/aidriven/app/WebhookRouter.java` |
| MODIFY | `jira-client/src/main/java/com/aidriven/jira/JiraClient.java` |
| MODIFY | `infrastructure/lib/ai-driven-stack.ts` |
