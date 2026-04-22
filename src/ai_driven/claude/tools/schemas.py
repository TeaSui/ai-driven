"""Common tool schema definitions shared across agents."""

# --- Jira Tools ---

GET_JIRA_ISSUE = {
    "name": "jira.get_issue",
    "description": (
        "Fetch full Jira issue details including summary, description, acceptance criteria, "
        "subtasks, comments, and status. Use when starting a new task or needing context about a ticket."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "issue_key": {
                "type": "string",
                "description": "Jira issue key, e.g. 'PROJ-123'",
            },
        },
        "required": ["issue_key"],
    },
}

UPDATE_JIRA_STATUS = {
    "name": "jira.update_status",
    "description": (
        "Transition a Jira issue to a new status. Use after completing a phase "
        "(e.g., moving from 'In Progress' to 'Code Review')."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "issue_key": {"type": "string", "description": "Jira issue key"},
            "transition_name": {"type": "string", "description": "Target status name, e.g. 'In Review'"},
        },
        "required": ["issue_key", "transition_name"],
    },
}

ADD_JIRA_COMMENT = {
    "name": "jira.add_comment",
    "description": "Add a comment to a Jira issue. Use to report progress or results.",
    "input_schema": {
        "type": "object",
        "properties": {
            "issue_key": {"type": "string", "description": "Jira issue key"},
            "body": {"type": "string", "description": "Comment text (supports Jira markdown)"},
        },
        "required": ["issue_key", "body"],
    },
}

# --- SCM Tools ---

LIST_REPO_FILES = {
    "name": "repo.list_files",
    "description": (
        "List files and directories in a repository path. "
        "Use to understand project structure before reading specific files."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name, e.g. 'org/repo'"},
            "path": {"type": "string", "description": "Directory path within repo, e.g. 'src/main'", "default": ""},
            "ref": {"type": "string", "description": "Branch or commit ref", "default": "main"},
        },
        "required": ["repo_full_name"],
    },
}

READ_FILE = {
    "name": "repo.read_file",
    "description": (
        "Read the full contents of a file from the repository. "
        "Use to understand existing code before making changes."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name"},
            "path": {"type": "string", "description": "File path within repo"},
            "ref": {"type": "string", "description": "Branch or commit ref", "default": "main"},
        },
        "required": ["repo_full_name", "path"],
    },
}

SEARCH_CODE = {
    "name": "code.search",
    "description": (
        "Search for code patterns across the repository using a query string. "
        "Use to find related implementations, usages, or definitions."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name"},
            "query": {"type": "string", "description": "Search query (code pattern or keyword)"},
        },
        "required": ["repo_full_name", "query"],
    },
}

GET_PR_DIFF = {
    "name": "github.get_pr_diff",
    "description": (
        "Fetch the diff of a pull request. Returns the unified diff of all changed files. "
        "Use when reviewing a PR."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name"},
            "pr_number": {"type": "integer", "description": "Pull request number"},
        },
        "required": ["repo_full_name", "pr_number"],
    },
}

CREATE_PULL_REQUEST = {
    "name": "github.create_pr",
    "description": (
        "Create a new pull request. Use after all code changes are committed and pushed. "
        "Returns the PR URL."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name"},
            "title": {"type": "string", "description": "PR title"},
            "body": {"type": "string", "description": "PR description (markdown)"},
            "source_branch": {"type": "string", "description": "Source branch name"},
            "target_branch": {"type": "string", "description": "Target branch name", "default": "main"},
        },
        "required": ["repo_full_name", "title", "body", "source_branch"],
    },
}

POST_PR_COMMENT = {
    "name": "github.post_pr_comment",
    "description": (
        "Post a review comment on a pull request. Can be a general comment or an inline comment "
        "on a specific file and line. Use during code review."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "repo_full_name": {"type": "string", "description": "Repository full name"},
            "pr_number": {"type": "integer", "description": "Pull request number"},
            "body": {"type": "string", "description": "Comment text (markdown)"},
            "file_path": {"type": "string", "description": "File path for inline comment (optional)"},
            "line": {"type": "integer", "description": "Line number for inline comment (optional)"},
        },
        "required": ["repo_full_name", "pr_number", "body"],
    },
}

# --- Execution Tools ---

EXECUTE_IN_SANDBOX = {
    "name": "sandbox.execute",
    "description": (
        "Run a shell command in the sandboxed environment (build, test, lint). "
        "Use to verify code changes compile and tests pass. Returns stdout and stderr."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "Shell command to execute"},
            "working_dir": {"type": "string", "description": "Working directory relative to repo root", "default": "."},
            "timeout_seconds": {"type": "integer", "description": "Command timeout in seconds", "default": 120},
        },
        "required": ["command"],
    },
}


# --- Tool Groups ---

CODING_AGENT_TOOLS = [
    GET_JIRA_ISSUE, UPDATE_JIRA_STATUS, ADD_JIRA_COMMENT,
    LIST_REPO_FILES, READ_FILE, SEARCH_CODE,
    CREATE_PULL_REQUEST, EXECUTE_IN_SANDBOX,
]

REVIEW_AGENT_TOOLS = [
    GET_PR_DIFF, READ_FILE, LIST_REPO_FILES, SEARCH_CODE,
    POST_PR_COMMENT,
]

DEVOPS_AGENT_TOOLS = [
    GET_JIRA_ISSUE, UPDATE_JIRA_STATUS, ADD_JIRA_COMMENT,
]
