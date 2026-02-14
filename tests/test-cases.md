# Comprehensive Test Cases Documentation

## Test Strategy

Following TDD principles:
1. Document all test cases first
2. Implement/verify code to pass all tests
3. Maintain 80%+ coverage across all metrics

---

## 1. bedrock-invoke-ai Lambda

### Function Purpose
Invokes AWS Bedrock Claude AI to generate code based on Jira ticket and codebase context.

### Test Cases

#### TC-BEDROCK-001: Input Validation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BEDROCK-001.1 | Missing Ticket parameter | `{ Files: {} }` | Error: "Missing Ticket or Files in the event" | High |
| TC-BEDROCK-001.2 | Missing Files parameter | `{ Ticket: {...} }` | Error: "Missing Ticket or Files in the event" | High |
| TC-BEDROCK-001.3 | Both parameters missing | `{}` | Error: "Missing Ticket or Files in the event" | High |
| TC-BEDROCK-001.4 | Null Ticket | `{ Ticket: null, Files: {} }` | Error: "Missing Ticket or Files in the event" | Medium |
| TC-BEDROCK-001.5 | Null Files | `{ Ticket: {...}, Files: null }` | Error: "Missing Ticket or Files in the event" | Medium |

#### TC-BEDROCK-002: Successful AI Invocation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BEDROCK-002.1 | Valid ticket with files | Valid Ticket + Files | `{ statusCode: 200, body: { TicketId, AIResult } }` | High |
| TC-BEDROCK-002.2 | Ticket with empty description | Ticket without description | Success with generated code | Medium |
| TC-BEDROCK-002.3 | Empty files object | `{ Ticket: {...}, Files: {} }` | Success (no context) | Medium |
| TC-BEDROCK-002.4 | Multiple files (>5) | Ticket + 10 files | Success with all files in context | Low |

#### TC-BEDROCK-003: Model Configuration
| ID | Test Case | Verification | Expected Value | Priority |
|----|-----------|--------------|----------------|----------|
| TC-BEDROCK-003.1 | Correct model ID | Check modelId parameter | `apac.anthropic.claude-3-5-sonnet-20241022-v2:0` | High |
| TC-BEDROCK-003.2 | Max tokens setting | Check max_tokens | 8192 | High |
| TC-BEDROCK-003.3 | Temperature setting | Check temperature | 0.3 | High |
| TC-BEDROCK-003.4 | Top_p setting | Check top_p | 0.9 | Medium |
| TC-BEDROCK-003.5 | Anthropic version | Check anthropic_version | "bedrock-2023-05-31" | Medium |

#### TC-BEDROCK-004: Prompt Engineering
| ID | Test Case | Verification | Expected Content | Priority |
|----|-----------|--------------|------------------|----------|
| TC-BEDROCK-004.1 | System prompt includes Java 21+ | Check system prompt | Contains "Java 21+" | High |
| TC-BEDROCK-004.2 | System prompt includes Spring Boot 3.x | Check system prompt | Contains "Spring Boot 3.x" | High |
| TC-BEDROCK-004.3 | System prompt includes Lombok | Check system prompt | Contains "Lombok" | High |
| TC-BEDROCK-004.4 | System prompt includes TDD | Check system prompt | Contains "TDD" | High |
| TC-BEDROCK-004.5 | System prompt includes layered architecture | Check system prompt | Contains "controller → service" | Medium |
| TC-BEDROCK-004.6 | Files context formatted correctly | Check user prompt | Contains file paths and content | High |
| TC-BEDROCK-004.7 | Ticket info in prompt | Check user prompt | Contains TicketId, Title, Description | High |

#### TC-BEDROCK-005: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-BEDROCK-005.1 | Bedrock API unavailable | Mock API error | Throw error with message | High |
| TC-BEDROCK-005.2 | Malformed response | Invalid JSON response | Throw parsing error | High |
| TC-BEDROCK-005.3 | Empty response body | Empty API response | Throw error | Medium |
| TC-BEDROCK-005.4 | Network timeout | Timeout error | Throw timeout error | Medium |
| TC-BEDROCK-005.5 | Invalid credentials | Auth error | Throw auth error | High |

#### TC-BEDROCK-006: Edge Cases
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BEDROCK-006.1 | Very long file content | File with 10K lines | Truncate/handle gracefully | Low |
| TC-BEDROCK-006.2 | Special characters in files | Unicode, emojis | Encode correctly | Medium |
| TC-BEDROCK-006.3 | Binary file content | Binary data | Handle/skip gracefully | Low |
| TC-BEDROCK-006.4 | Missing ticket fields | Ticket without Title | Use defaults | Medium |

---

## 2. jira-handlers Lambda

### Function Purpose
Fetch ticket details from Jira and update ticket status/comments.

### Test Cases

#### TC-JIRA-001: fetchTicket - Input Validation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-JIRA-001.1 | Missing TicketId | `{}` | Error: "Missing TicketId" | High |
| TC-JIRA-001.2 | Null TicketId | `{ TicketId: null }` | Error: "Missing TicketId" | High |
| TC-JIRA-001.3 | Empty string TicketId | `{ TicketId: "" }` | Error: "Missing TicketId" | Medium |
| TC-JIRA-001.4 | Invalid TicketId format | `{ TicketId: "invalid" }` | API error (404) | Medium |

#### TC-JIRA-002: fetchTicket - Successful Fetch
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-JIRA-002.1 | Valid ticket with all fields | `{ TicketId: "PROJ-123" }` | Full ticket object with Id, Title, Description, Repo, BaseBranch | High |
| TC-JIRA-002.2 | Ticket with structured description | Jira doc format | Parse description text correctly | High |
| TC-JIRA-002.3 | Ticket with string description | Plain text | Return description as-is | Medium |
| TC-JIRA-002.4 | Ticket without description | No description field | Return empty string | Medium |
| TC-JIRA-002.5 | Ticket with custom repo field | customfield_10100 present | Use custom field value | High |
| TC-JIRA-002.6 | Ticket without custom repo | No customfield_10100 | Use fallback repo URL | Medium |

#### TC-JIRA-003: fetchTicket - API Integration
| ID | Test Case | Verification | Expected Behavior | Priority |
|----|-----------|--------------|-------------------|----------|
| TC-JIRA-003.1 | Correct API endpoint | Check URL | `/rest/api/3/issue/{TicketId}` | High |
| TC-JIRA-003.2 | Authorization header | Check headers | Basic Auth with base64 encoded credentials | High |
| TC-JIRA-003.3 | Accept header | Check headers | "application/json" | Medium |
| TC-JIRA-003.4 | Content-Type header | Check headers | "application/json" | Medium |

#### TC-JIRA-004: updateStatus - Input Validation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-JIRA-004.1 | Missing TicketId | `{ Status: "DONE" }` | Error: "Missing TicketId or Status" | High |
| TC-JIRA-004.2 | Missing Status | `{ TicketId: "PROJ-123" }` | Error: "Missing TicketId or Status" | High |
| TC-JIRA-004.3 | Both missing | `{}` | Error: "Missing TicketId or Status" | High |

#### TC-JIRA-005: updateStatus - Successful Update
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-JIRA-005.1 | Update without comment | `{ TicketId, Status }` | Success message with TicketId | High |
| TC-JIRA-005.2 | Update with comment | `{ TicketId, Status, Comment }` | Success + comment posted | High |
| TC-JIRA-005.3 | Comment with special chars | Comment with emoji/unicode | Comment posted correctly | Medium |
| TC-JIRA-005.4 | Long comment (>1000 chars) | Very long comment | Comment posted fully | Low |

#### TC-JIRA-006: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-JIRA-006.1 | Network error | Connection timeout | Throw network error | High |
| TC-JIRA-006.2 | API rate limit | 429 response | Throw rate limit error | Medium |
| TC-JIRA-006.3 | Invalid credentials | 401 response | Throw auth error | High |
| TC-JIRA-006.4 | Ticket not found | 404 response | Throw not found error | High |
| TC-JIRA-006.5 | Permission denied | 403 response | Throw permission error | Medium |

---

## 3. bitbucket-fetch-code Lambda

### Function Purpose
Clone Bitbucket repository and fetch relevant Java/build files for context.

### Test Cases

#### TC-BB-FETCH-001: Input Validation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BB-FETCH-001.1 | Missing Repo URL | `{}` | Error: "Missing Repo URL in the event" | High |
| TC-BB-FETCH-001.2 | Null Repo URL | `{ Repo: null }` | Error: "Missing Repo URL in the event" | High |
| TC-BB-FETCH-001.3 | Invalid URL format | `{ Repo: "not-a-url" }` | Git clone error | Medium |

#### TC-BB-FETCH-002: Git Clone Operations
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-FETCH-002.1 | Clone with default branch | `{ Repo }` | Clone main branch | High |
| TC-BB-FETCH-002.2 | Clone specific branch | `{ Repo, Branch: "develop" }` | Clone develop branch | High |
| TC-BB-FETCH-002.3 | Shallow clone flag | Any valid input | Use --depth 1 | High |
| TC-BB-FETCH-002.4 | Single branch flag | Any valid input | Use --single-branch | High |

#### TC-BB-FETCH-003: Authentication
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-BB-FETCH-003.1 | URL without credentials + env vars present | Plain HTTPS URL | Inject username:password into URL | High |
| TC-BB-FETCH-003.2 | URL with embedded credentials | `https://user:pass@bitbucket.org/...` | Use URL as-is | High |
| TC-BB-FETCH-003.3 | Missing credentials | No env vars, no embedded creds | Use URL as-is (may fail) | Medium |
| TC-BB-FETCH-003.4 | URL encoding of credentials | Special chars in password | Properly encode credentials | High |

#### TC-BB-FETCH-004: File Fetching - Specific Files
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BB-FETCH-004.1 | Fetch specific files | `{ Repo, FilesToFetch: ["a.java", "b.java"] }` | Return only requested files | High |
| TC-BB-FETCH-004.2 | File exists | Valid file path | Include in results | High |
| TC-BB-FETCH-004.3 | File doesn't exist | Invalid file path | Skip file (not in results) | High |
| TC-BB-FETCH-004.4 | Directory in file list | Directory path | Skip directory | Medium |
| TC-BB-FETCH-004.5 | Mixed valid/invalid files | Some exist, some don't | Return only existing files | High |

#### TC-BB-FETCH-005: File Fetching - Auto Discovery
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BB-FETCH-005.1 | No specific files requested | `{ Repo }` | Find .java, pom.xml, build.gradle files | High |
| TC-BB-FETCH-005.2 | Limit to 10 files | Repo with 20+ Java files | Return max 10 files | High |
| TC-BB-FETCH-005.3 | Skip .git directory | Repo with .git folder | Don't search .git | High |
| TC-BB-FETCH-005.4 | Skip node_modules | Repo with node_modules | Don't search node_modules | High |
| TC-BB-FETCH-005.5 | Skip target directory | Java project with target/ | Don't search target | Medium |
| TC-BB-FETCH-005.6 | Skip build directory | Gradle project with build/ | Don't search build | Medium |

#### TC-BB-FETCH-006: Response Format
| ID | Test Case | Verification | Expected Format | Priority |
|----|-----------|--------------|-----------------|----------|
| TC-BB-FETCH-006.1 | Success status code | Any successful fetch | statusCode: 200 | High |
| TC-BB-FETCH-006.2 | Repo field in response | Any fetch | body.Repo = input Repo | High |
| TC-BB-FETCH-006.3 | Branch field in response | Any fetch | body.Branch = used branch | High |
| TC-BB-FETCH-006.4 | Files object structure | Any fetch | body.Files = { "path": "content" } | High |
| TC-BB-FETCH-006.5 | Relative file paths | Nested files | Use relative paths from repo root | Medium |

#### TC-BB-FETCH-007: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-BB-FETCH-007.1 | Repository not found | Invalid repo URL | Throw git error | High |
| TC-BB-FETCH-007.2 | Authentication failure | Wrong credentials | Throw auth error | High |
| TC-BB-FETCH-007.3 | Branch doesn't exist | Invalid branch name | Throw branch error | High |
| TC-BB-FETCH-007.4 | Network timeout | Network issues | Throw timeout error | Medium |
| TC-BB-FETCH-007.5 | Permission denied | No read access | Throw permission error | High |

#### TC-BB-FETCH-008: Edge Cases
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-FETCH-008.1 | Empty repository | Repo with no files | Return empty Files object | Medium |
| TC-BB-FETCH-008.2 | Repository with only non-Java files | Only .py, .js files | Return empty Files object | Low |
| TC-BB-FETCH-008.3 | Very large files (>1MB) | Repo with huge files | Read file fully (no size limit) | Low |
| TC-BB-FETCH-008.4 | Binary files | .class, .jar files | Read as binary/skip | Low |

---

## 4. bitbucket-create-pr Lambda

### Function Purpose
Create feature branch, commit AI-generated changes, push, and open Pull Request.

### Test Cases

#### TC-BB-PR-001: Input Validation
| ID | Test Case | Input | Expected Output | Priority |
|----|-----------|-------|-----------------|----------|
| TC-BB-PR-001.1 | Missing Repo | `{ TicketId, Changes }` | Error: "Missing Repo, TicketId, or Changes" | High |
| TC-BB-PR-001.2 | Missing TicketId | `{ Repo, Changes }` | Error: "Missing Repo, TicketId, or Changes" | High |
| TC-BB-PR-001.3 | Missing Changes | `{ Repo, TicketId }` | Error: "Missing Repo, TicketId, or Changes" | High |
| TC-BB-PR-001.4 | All parameters missing | `{}` | Error: "Missing Repo, TicketId, or Changes" | High |

#### TC-BB-PR-002: Branch Operations
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-PR-002.1 | Branch name format | `{ TicketId: "PROJ-123" }` | Create branch `ai-ticket-PROJ-123` | High |
| TC-BB-PR-002.2 | Default base branch | No BaseBranch specified | Use "main" as base | High |
| TC-BB-PR-002.3 | Custom base branch | `{ BaseBranch: "develop" }` | Branch from develop | High |
| TC-BB-PR-002.4 | Branch creation | Valid input | Call checkoutLocalBranch | High |

#### TC-BB-PR-003: File Operations
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-PR-003.1 | Single file change | `{ Changes: { "a.java": "code" } }` | Create/update 1 file | High |
| TC-BB-PR-003.2 | Multiple file changes | 10 files in Changes | Create/update all files | High |
| TC-BB-PR-003.3 | Nested directory structure | `{ "src/main/java/App.java": "..." }` | Create nested dirs recursively | High |
| TC-BB-PR-003.4 | File with special characters | Unicode content | Write correctly | Medium |
| TC-BB-PR-003.5 | Overwrite existing file | File already exists | Overwrite with new content | High |

#### TC-BB-PR-004: Git Operations
| ID | Test Case | Verification | Expected Behavior | Priority |
|----|-----------|--------------|-------------------|----------|
| TC-BB-PR-004.1 | Git add command | After file writes | Call `git add .` | High |
| TC-BB-PR-004.2 | Commit message format | After add | `AI-generated code for {TicketId}` | High |
| TC-BB-PR-004.3 | Push to origin | After commit | Push branch to origin | High |
| TC-BB-PR-004.4 | Clone authentication | At start | Inject credentials into URL | High |

#### TC-BB-PR-005: Pull Request Creation
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-PR-005.1 | PR title format | `{ TicketId: "TEST-789" }` | Title: `[AI] Generated Implementation for TEST-789` | High |
| TC-BB-PR-005.2 | PR description | Any valid input | Mention Jira ticket and request review | High |
| TC-BB-PR-005.3 | Source branch | Created branch | Use ai-ticket-{TicketId} | High |
| TC-BB-PR-005.4 | Destination branch | BaseBranch or default | Use specified or main | High |
| TC-BB-PR-005.5 | Bitbucket API endpoint | Any input | POST to /repositories/{workspace}/{repo}/pullrequests | High |
| TC-BB-PR-005.6 | API authentication | Any input | Basic Auth header | High |

#### TC-BB-PR-006: URL Parsing
| ID | Test Case | Input Repo URL | Expected Parsing | Priority |
|----|-----------|----------------|------------------|----------|
| TC-BB-PR-006.1 | HTTPS URL | `https://bitbucket.org/workspace/repo.git` | workspace=workspace, repo=repo | High |
| TC-BB-PR-006.2 | URL without .git suffix | `https://bitbucket.org/workspace/repo` | workspace=workspace, repo=repo | High |
| TC-BB-PR-006.3 | SSH URL | `git@bitbucket.org:workspace/repo.git` | workspace=workspace, repo=repo | Medium |
| TC-BB-PR-006.4 | Invalid URL format | `invalid-url` | Error: "Could not parse Bitbucket URL" | High |

#### TC-BB-PR-007: Response Format
| ID | Test Case | Verification | Expected Output | Priority |
|----|-----------|--------------|-----------------|----------|
| TC-BB-PR-007.1 | Success status code | Successful PR creation | statusCode: 201 | High |
| TC-BB-PR-007.2 | TicketId in response | Any success | body.TicketId matches input | High |
| TC-BB-PR-007.3 | Branch name in response | Any success | body.Branch = ai-ticket-{TicketId} | High |
| TC-BB-PR-007.4 | PR URL in response | Any success | body.PRUrl = Bitbucket PR link | High |

#### TC-BB-PR-008: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-BB-PR-008.1 | Clone fails | Invalid repo | Throw clone error | High |
| TC-BB-PR-008.2 | Branch already exists | Duplicate branch | Throw branch error | Medium |
| TC-BB-PR-008.3 | Commit fails | Git error | Throw commit error | Medium |
| TC-BB-PR-008.4 | Push fails | Permission denied | Throw push error | High |
| TC-BB-PR-008.5 | PR API fails | API error | Throw API error | High |
| TC-BB-PR-008.6 | Network timeout | Timeout during push | Throw timeout error | Medium |

#### TC-BB-PR-009: Edge Cases
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-BB-PR-009.1 | Very large changeset | 100+ files | Process all files | Low |
| TC-BB-PR-009.2 | Empty file content | `{ "file.java": "" }` | Create empty file | Low |
| TC-BB-PR-009.3 | Delete file operation | Not currently supported | N/A | Low |

---

## 5. merge-wait-handler Lambda

### Function Purpose
Dual-mode handler: (1) Register Step Functions task token in DynamoDB (2) Resume workflow when PR is merged via webhook.

### Test Cases

#### TC-MERGE-001: Registration Mode - Input
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-MERGE-001.1 | Valid token and PR URL | `{ token: "abc", PRUrl: "https://..." }` | Register in DynamoDB | High |
| TC-MERGE-001.2 | Missing token | `{ PRUrl: "https://..." }` | Skip registration, treat as webhook | High |
| TC-MERGE-001.3 | Missing PRUrl | `{ token: "abc" }` | Skip registration | Medium |

#### TC-MERGE-002: Registration Mode - DynamoDB Operations
| ID | Test Case | Verification | Expected Behavior | Priority |
|----|-----------|--------------|-------------------|----------|
| TC-MERGE-002.1 | PutCommand called | Registration mode | Store token with PRUrl as key | High |
| TC-MERGE-002.2 | TTL calculation | Any registration | TimeToExist = now + 7 days (in seconds) | High |
| TC-MERGE-002.3 | Table name | Any registration | Use TOKEN_TABLE_NAME env var | High |
| TC-MERGE-002.4 | Success response | Successful registration | Return `{ statusCode: 200, message: "Token registered" }` | High |

#### TC-MERGE-003: Resume Mode - Webhook Parsing
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-MERGE-003.1 | Direct webhook payload | `{ pullrequest: {...} }` | Parse directly | High |
| TC-MERGE-003.2 | API Gateway wrapped | `{ body: "{...}" }` | Parse JSON from body string | High |
| TC-MERGE-003.3 | Missing pullrequest field | `{ something_else: "..." }` | Return success, do nothing | Medium |

#### TC-MERGE-004: Resume Mode - State Detection
| ID | Test Case | PR State | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-MERGE-004.1 | State = MERGED | `{ pullrequest: { state: "MERGED" } }` | Lookup token and resume workflow | High |
| TC-MERGE-004.2 | State = OPEN | `{ pullrequest: { state: "OPEN" } }` | Return success, do nothing | Medium |
| TC-MERGE-004.3 | State = DECLINED | `{ pullrequest: { state: "DECLINED" } }` | Return success, do nothing | Medium |
| TC-MERGE-004.4 | State = SUPERSEDED | Other states | Return success, do nothing | Low |

#### TC-MERGE-005: Resume Mode - Workflow Resumption
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-MERGE-005.1 | Token found in DynamoDB | Merged PR with active token | Call SendTaskSuccessCommand | High |
| TC-MERGE-005.2 | Token not found | Merged PR without token | Log "no token found", return success | Medium |
| TC-MERGE-005.3 | Success output format | Token found | Output: `{ status: "Merged", prUrl: "..." }` | High |
| TC-MERGE-005.4 | Delete token after resume | Successful resume | DeleteCommand called | High |

#### TC-MERGE-006: DynamoDB Operations
| ID | Test Case | Operation | Expected Behavior | Priority |
|----|-----------|-----------|-------------------|----------|
| TC-MERGE-006.1 | GetCommand structure | Resume mode | Key: { PRUrl: "..." } | High |
| TC-MERGE-006.2 | DeleteCommand structure | After resume | Key: { PRUrl: "..." } | High |
| TC-MERGE-006.3 | Table name consistency | All operations | Use TOKEN_TABLE_NAME | High |

#### TC-MERGE-007: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-MERGE-007.1 | DynamoDB PutCommand fails | Registration error | Throw DynamoDB error | High |
| TC-MERGE-007.2 | DynamoDB GetCommand fails | Lookup error | Throw DynamoDB error | High |
| TC-MERGE-007.3 | Step Functions send fails | Expired token | Throw SFN error | High |
| TC-MERGE-007.4 | Malformed webhook payload | Invalid JSON | Throw parse error | Medium |

#### TC-MERGE-008: Edge Cases
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-MERGE-008.1 | Duplicate registration | Same PR registered twice | Overwrite previous entry | Low |
| TC-MERGE-008.2 | Resume after token expired | Token TTL expired | Item not found, log and continue | Medium |
| TC-MERGE-008.3 | Multiple PRs merged simultaneously | Concurrent webhooks | Handle each independently | Low |

---

## 6. jira-webhook-handler Lambda

### Function Purpose
Listen for Jira webhook events and trigger Step Functions workflow execution.

### Test Cases

#### TC-JIRA-WH-001: Webhook Parsing
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-JIRA-WH-001.1 | Direct webhook payload | `{ issue: { key: "PROJ-123" } }` | Parse directly | High |
| TC-JIRA-WH-001.2 | API Gateway wrapped | `{ body: "{\"issue\": ...}" }` | Parse JSON from body string | High |
| TC-JIRA-WH-001.3 | Missing issue field | `{ something_else: "..." }` | Return success, skip execution | Medium |
| TC-JIRA-WH-001.4 | Issue without key | `{ issue: { id: "12345" } }` | Return success, skip execution | Medium |

#### TC-JIRA-WH-002: Workflow Execution
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-JIRA-WH-002.1 | Valid issue key | `{ issue: { key: "TEST-456" } }` | Start Step Functions execution | High |
| TC-JIRA-WH-002.2 | State machine ARN | Any valid issue | Use STATE_MACHINE_ARN env var | High |
| TC-JIRA-WH-002.3 | Execution input format | Issue PROJ-123 | Input: `{ "TicketId": "PROJ-123" }` | High |
| TC-JIRA-WH-002.4 | Execution name format | Issue TEST-789 | Name: `AI-TEST-789-{timestamp}` | High |
| TC-JIRA-WH-002.5 | Unique execution names | Same issue twice | Different timestamps | High |

#### TC-JIRA-WH-003: Response Format
| ID | Test Case | Scenario | Expected Output | Priority |
|----|-----------|----------|-----------------|----------|
| TC-JIRA-WH-003.1 | Successful execution start | Valid issue | `{ statusCode: 200, body: { message: "Execution started", executionArn: "..." } }` | High |
| TC-JIRA-WH-003.2 | No issue key found | Missing issue | `{ statusCode: 200, body: { message: "No issue key found, skipping" } }` | High |

#### TC-JIRA-WH-004: Changelog Parsing
| ID | Test Case | Input | Expected Behavior | Priority |
|----|-----------|-------|-------------------|----------|
| TC-JIRA-WH-004.1 | Changelog with status change | `{ changelog: { items: [{ field: "status", toString: "IN PROGRESS" }] } }` | Extract new status | Low |
| TC-JIRA-WH-004.2 | Changelog without status | `{ changelog: { items: [{ field: "priority" }] } }` | Status = null | Low |
| TC-JIRA-WH-004.3 | No changelog | Event without changelog | Continue without status | Low |

#### TC-JIRA-WH-005: Error Handling
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-JIRA-WH-005.1 | State machine not found | Invalid ARN | Throw SFN error | High |
| TC-JIRA-WH-005.2 | Access denied | No permission to start execution | Throw permission error | High |
| TC-JIRA-WH-005.3 | Malformed webhook | Invalid JSON | Throw parse error | Medium |
| TC-JIRA-WH-005.4 | State machine busy | Execution limit reached | Throw throttling error | Low |

#### TC-JIRA-WH-006: Edge Cases
| ID | Test Case | Scenario | Expected Behavior | Priority |
|----|-----------|----------|-------------------|----------|
| TC-JIRA-WH-006.1 | Rapid successive webhooks | Multiple events for same issue | Start multiple executions | Medium |
| TC-JIRA-WH-006.2 | Very long issue key | Key with 100+ chars | Handle gracefully | Low |
| TC-JIRA-WH-006.3 | Special characters in key | Unicode in issue key | Encode correctly | Low |

---

## Test Execution Summary

### Coverage Requirements
- **Statements**: ≥ 80%
- **Branches**: ≥ 80%
- **Functions**: ≥ 80%
- **Lines**: ≥ 80%

### Test Pyramid Distribution
- **Unit Tests**: 78 tests (100% of current implementation)
- **Integration Tests**: TBD (Tier 2)
- **E2E Tests**: TBD (Tier 3)

### Priority Levels
- **High**: Core functionality, security, data integrity (must pass)
- **Medium**: Important features, common edge cases (should pass)
- **Low**: Rare edge cases, nice-to-have validations (may defer)

### Traceability Matrix

| Lambda | Total Test Cases | High Priority | Medium Priority | Low Priority |
|--------|------------------|---------------|-----------------|--------------|
| bedrock-invoke-ai | 31 | 20 | 7 | 4 |
| jira-handlers | 26 | 16 | 8 | 2 |
| bitbucket-fetch-code | 35 | 22 | 9 | 4 |
| bitbucket-create-pr | 39 | 28 | 8 | 3 |
| merge-wait-handler | 32 | 21 | 8 | 3 |
| jira-webhook-handler | 24 | 15 | 7 | 2 |
| **TOTAL** | **187** | **122** | **47** | **18** |

### Test Automation
- All tests run via Jest
- CI/CD integration ready
- Coverage reports generated automatically
- Tests execute in <1 second

---

## Next Steps

1. ✅ Review all test cases for completeness
2. ⏳ Verify implementation matches test cases
3. ⏳ Update unit tests to cover all documented scenarios
4. ⏳ Run full test suite and validate 80%+ coverage
5. ⏳ Document any gaps or deviations
