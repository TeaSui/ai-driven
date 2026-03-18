package com.aidriven.mcp.server;

import com.aidriven.core.source.RepositoryReader;
import com.aidriven.github.GitHubClient;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone MCP server for GitHub operations.
 *
 * <p>Exposes core GitHub functionality as MCP tools, consumed by
 * {@code McpBridgeToolProvider} running inside the agent Lambda.</p>
 *
 * <h2>Tools exposed</h2>
 * <ul>
 *   <li>{@code get_file} — read a file from the repository</li>
 *   <li>{@code create_branch} — create a branch from a source branch</li>
 *   <li>{@code get_default_branch} — return the repository default branch name</li>
 *   <li>{@code list_files} — list files under an optional path prefix on a branch</li>
 *   <li>{@code push_file} — create or overwrite a single file and commit</li>
 *   <li>{@code create_pr} — open a pull request from source → base branch</li>
 *   <li>{@code list_prs} — list open pull requests</li>
 *   <li>{@code add_pr_comment} — post a top-level comment on a PR</li>
 * </ul>
 *
 * <h2>Required environment variables</h2>
 * <ul>
 *   <li>{@code GITHUB_OWNER} — repository owner (user or org)</li>
 *   <li>{@code GITHUB_REPO} — repository name</li>
 *   <li>{@code GITHUB_TOKEN} — personal access token with repo scope</li>
 * </ul>
 */
@Slf4j
public class GithubMcpServer {

    public static void main(String[] args) {
        log.info("Starting GitHub MCP Server...");

        String owner = System.getenv("GITHUB_OWNER");
        String repo  = System.getenv("GITHUB_REPO");
        String token = System.getenv("GITHUB_TOKEN");

        if (owner == null || repo == null || token == null) {
            log.error("Missing required environment variables: GITHUB_OWNER, GITHUB_REPO, GITHUB_TOKEN");
            System.exit(1);
        }

        GitHubClient githubClient = new GitHubClient(owner, repo, token);

        OperationContext defaultContext = OperationContext.builder()
                .tenantId("default")
                .requestId("mcp-default")
                .build();

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider())
                .serverInfo(new McpSchema.Implementation("github-mcp-server", "1.0.0"))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        // ── get_file ─────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_file",
                        "Read the content of a file from the repository.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "path",   Map.of("type", "string", "description",
                                                         "Path to the file relative to repo root"),
                                        "branch", Map.of("type", "string", "description",
                                                         "Branch name (defaults to main)")),
                                List.of("path"), null, null, null)),
                (exchange, arguments) -> {
                    String path   = (String) arguments.get("path");
                    String branch = arguments.containsKey("branch") ? (String) arguments.get("branch") : "main";
                    try {
                        String content = githubClient.getFileContent(defaultContext, BranchName.of(branch), path);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(content)), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── create_branch ─────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "create_branch",
                        "Create a new branch from a source branch.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "new_branch",    Map.of("type", "string", "description",
                                                                 "Name of the new branch"),
                                        "source_branch", Map.of("type", "string", "description",
                                                                 "Name of the source branch")),
                                List.of("new_branch", "source_branch"), null, null, null)),
                (exchange, arguments) -> {
                    String newBranch    = (String) arguments.get("new_branch");
                    String sourceBranch = (String) arguments.get("source_branch");
                    try {
                        githubClient.createBranch(
                                defaultContext, BranchName.of(newBranch), BranchName.of(sourceBranch));
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "Branch '" + newBranch + "' created successfully")),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── get_default_branch ────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_default_branch",
                        "Return the default branch name for the repository (e.g. 'main' or 'master').",
                        new McpSchema.JsonSchema("object",
                                Map.of(), List.of(), null, null, null)),
                (exchange, arguments) -> {
                    try {
                        String defaultBranch = githubClient.getDefaultBranch(defaultContext).name();
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(defaultBranch)), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── list_files ────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "list_files",
                        "List all files in the repository on a given branch, "
                                + "optionally filtered by path prefix.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "branch",      Map.of("type", "string", "description",
                                                              "Branch name (defaults to main)"),
                                        "path_prefix", Map.of("type", "string", "description",
                                                              "Only return files whose path starts with this "
                                                                      + "prefix (optional)")),
                                List.of("branch"), null, null, null)),
                (exchange, arguments) -> {
                    String branch     = arguments.containsKey("branch")
                            ? (String) arguments.get("branch") : "main";
                    String pathPrefix = arguments.containsKey("path_prefix")
                            ? (String) arguments.get("path_prefix") : null;
                    try {
                        List<String> files = githubClient.getFileTree(
                                defaultContext, BranchName.of(branch), pathPrefix);
                        if (files.isEmpty()) {
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent("No files found")), false);
                        }
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(String.join("\n", files))), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── push_file ─────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "push_file",
                        "Create or overwrite a single file in the repository and commit it "
                                + "to the specified branch.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "branch",         Map.of("type", "string", "description",
                                                                 "Target branch name"),
                                        "path",           Map.of("type", "string", "description",
                                                                 "File path relative to repo root"),
                                        "content",        Map.of("type", "string", "description",
                                                                 "New file content (UTF-8)"),
                                        "commit_message", Map.of("type", "string", "description",
                                                                 "Commit message")),
                                List.of("branch", "path", "content", "commit_message"), null, null, null)),
                (exchange, arguments) -> {
                    String branch        = (String) arguments.get("branch");
                    String path          = (String) arguments.get("path");
                    String content       = (String) arguments.get("content");
                    String commitMessage = (String) arguments.get("commit_message");
                    try {
                        String commitSha = githubClient.pushFiles(
                                defaultContext,
                                BranchName.of(branch),
                                List.of(new SourceControlProvider.RepoFile(path, content, "upsert")),
                                commitMessage);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "File pushed successfully. Commit SHA: " + commitSha)),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── create_pr ─────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "create_pr",
                        "Open a pull request from a source branch into a base branch.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "title",         Map.of("type", "string", "description",
                                                                "PR title"),
                                        "description",   Map.of("type", "string", "description",
                                                                "PR body / description (Markdown, optional)"),
                                        "source_branch", Map.of("type", "string", "description",
                                                                "Source branch containing the changes"),
                                        "base_branch",   Map.of("type", "string", "description",
                                                                "Target base branch (e.g. main)")),
                                List.of("title", "source_branch", "base_branch"), null, null, null)),
                (exchange, arguments) -> {
                    String title        = (String) arguments.get("title");
                    String description  = arguments.containsKey("description")
                            ? (String) arguments.get("description") : "";
                    String sourceBranch = (String) arguments.get("source_branch");
                    String baseBranch   = (String) arguments.get("base_branch");
                    try {
                        SourceControlProvider.PullRequestResult pr = githubClient.createPullRequest(
                                defaultContext,
                                title,
                                description,
                                BranchName.of(sourceBranch),
                                BranchName.of(baseBranch));
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "PR #" + pr.id() + " created: " + pr.url())),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── list_prs ──────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "list_prs",
                        "List open pull requests in the repository.",
                        new McpSchema.JsonSchema("object",
                                Map.of(), List.of(), null, null, null)),
                (exchange, arguments) -> {
                    try {
                        List<RepositoryReader.PullRequestSummary> prs =
                                githubClient.listPullRequests(defaultContext);
                        if (prs.isEmpty()) {
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent("No open pull requests")), false);
                        }
                        String result = prs.stream()
                                .map(pr -> "#" + pr.id()
                                        + " — " + pr.title()
                                        + " (branch: " + pr.branch().name() + ")"
                                        + " → " + pr.url())
                                .collect(Collectors.joining("\n"));
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(result)), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── add_pr_comment ────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "add_pr_comment",
                        "Post a top-level comment on a pull request.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "pr_number", Map.of("type", "string", "description",
                                                            "Pull request number as a string (e.g. '42')"),
                                        "body",      Map.of("type", "string", "description",
                                                            "Comment text (Markdown supported)")),
                                List.of("pr_number", "body"), null, null, null)),
                (exchange, arguments) -> {
                    String prNumber = (String) arguments.get("pr_number");
                    String body     = (String) arguments.get("body");
                    try {
                        String commentId = githubClient.addPrComment(defaultContext, prNumber, body);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "Comment posted successfully. Comment ID: " + commentId)),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        log.info("GitHub MCP Server is running with {} tools — "
                + "waiting for JSON-RPC messages on stdin...", 8);
    }
}
