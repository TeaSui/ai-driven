package com.aidriven.tool.source;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.ToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference; // Often needed for convertValue
import com.fasterxml.jackson.databind.ObjectMapper; // Often needed
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Exposes {@link SourceControlClient} operations as Claude tools.
 *
 * <p>
 * Tools: get_file, search_files, get_file_tree, create_branch, commit_files,
 * create_pr
 * </p>
 */
@Slf4j
public class SourceControlToolProvider implements ToolProvider {

    private final SourceControlClient client;
    private final String defaultBranch;

    public SourceControlToolProvider(SourceControlClient client) {
        this(client, null);
    }

    public SourceControlToolProvider(SourceControlClient client, String defaultBranch) {
        this.client = client;
        this.defaultBranch = defaultBranch;
    }

    @Override
    public String namespace() {
        return "source_control";
    }

    @Override
    public List<Tool> toolDefinitions() {
        return List.of(
                Tool.of("source_control_get_file",
                        "Read the content of a file from the repository.",
                        Map.of(
                                "branch", Tool.stringProp("Branch name (default: main)"),
                                "file_path", Tool.stringProp("Path to the file")),
                        "file_path"),

                Tool.of("source_control_search_files",
                        "Search for files matching a query in the repository.",
                        Map.of("query", Tool.stringProp("Search query")),
                        "query"),

                Tool.of("source_control_get_file_tree",
                        "List all files in the repository or a subdirectory.",
                        Map.of(
                                "branch", Tool.stringProp("Branch name (default: main)"),
                                "path", Tool.stringProp("Optional path prefix to filter")),
                        "branch"),

                Tool.of("source_control_create_branch",
                        "Create a new branch from an existing branch.",
                        Map.of(
                                "branch_name", Tool.stringProp("New branch name"),
                                "from_branch", Tool.stringProp("Source branch (default: main)")),
                        "branch_name"),

                Tool.of("source_control_commit_files",
                        "Commit file changes to a branch. Use for creating or modifying files.",
                        Map.of(
                                "branch_name", Tool.stringProp("Target branch"),
                                "commit_message", Tool.stringProp("Commit message"),
                                "files", Tool.objectArrayProp("Files to commit", Map.of(
                                        "path", Tool.stringProp("File path"),
                                        "content", Tool.stringProp("File content"),
                                        "operation", Tool.stringProp("CREATE, UPDATE, or DELETE")))),
                        "branch_name", "commit_message", "files"),

                Tool.of("source_control_create_pr",
                        "Create a pull request.",
                        Map.of(
                                "title", Tool.stringProp("PR title"),
                                "description", Tool.stringProp("PR description"),
                                "source_branch", Tool.stringProp("Source branch"),
                                "destination_branch", Tool.stringProp("Target branch (default: main)")),
                        "title", "source_branch"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();

        try {
            return switch (action) {
                case "get_file" -> getFile(call.id(), input);
                case "search_files" -> searchFiles(call.id(), input);
                case "get_file_tree" -> getFileTree(call.id(), input);
                case "create_branch" -> createBranch(call.id(), input);
                case "commit_files" -> commitFiles(call.id(), input);
                case "create_pr" -> createPr(call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown action: " + action);
            };
        } catch (Exception e) {
            log.error("Source control tool error: {} - {}", action, e.getMessage(), e);
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    private ToolResult getFile(String toolUseId, JsonNode input) throws Exception {
        String branch = input.has("branch") ? input.get("branch").asText() : resolveBranch();
        String filePath = input.get("file_path").asText();

        String content = client.getFileContent(branch, filePath);
        if (content == null) {
            return ToolResult.error(toolUseId, "File not found: " + filePath + " on branch " + branch);
        }
        return ToolResult.success(toolUseId, content);
    }

    private ToolResult searchFiles(String toolUseId, JsonNode input) throws Exception {
        String query = input.get("query").asText();
        List<String> results = client.searchFiles(query);
        if (results.isEmpty()) {
            return ToolResult.success(toolUseId, "No files found matching: " + query);
        }
        return ToolResult.success(toolUseId, "Found %d files:\n%s",
                results.size(), String.join("\n", results));
    }

    private ToolResult getFileTree(String toolUseId, JsonNode input) throws Exception {
        String branch = input.has("branch") ? input.get("branch").asText() : resolveBranch();
        String path = input.has("path") ? input.get("path").asText() : null;

        List<String> files = client.getFileTree(branch, path);
        return ToolResult.success(toolUseId, "File tree (%d files):\n%s",
                files.size(), String.join("\n", files));
    }

    private ToolResult createBranch(String toolUseId, JsonNode input) throws Exception {
        String branchName = input.get("branch_name").asText();
        String fromBranch = input.has("from_branch") ? input.get("from_branch").asText() : resolveBranch();

        client.createBranch(branchName, fromBranch);
        return ToolResult.success(toolUseId, "Branch '%s' created from '%s'", branchName, fromBranch);
    }

    private ToolResult commitFiles(String toolUseId, JsonNode input) throws Exception {
        String branchName = input.get("branch_name").asText();
        String commitMessage = input.get("commit_message").asText();

        List<AgentResult.GeneratedFile> files = new ArrayList<>();
        for (JsonNode fileNode : input.get("files")) {
            String opStr = fileNode.has("operation") ? fileNode.get("operation").asText().toUpperCase() : "CREATE";
            // Normalize common aliases
            if ("MODIFY".equals(opStr)) opStr = "UPDATE";
            AgentResult.FileOperation operation;
            try {
                operation = AgentResult.FileOperation.valueOf(opStr);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown file operation '{}', defaulting to CREATE", opStr);
                operation = AgentResult.FileOperation.CREATE;
            }
            files.add(new AgentResult.GeneratedFile(
                    fileNode.get("path").asText(),
                    fileNode.get("content").asText(),
                    operation));
        }

        String commitId = client.commitFiles(branchName, files, commitMessage);
        return ToolResult.success(toolUseId, "Committed %d file(s) to '%s' (commit: %s)",
                files.size(), branchName, commitId);
    }

    private ToolResult createPr(String toolUseId, JsonNode input) throws Exception {
        String title = input.get("title").asText();
        String description = input.has("description") ? input.get("description").asText() : "";
        String sourceBranch = input.get("source_branch").asText();
        String destBranch = input.has("destination_branch")
                ? input.get("destination_branch").asText()
                : resolveBranch();

        SourceControlClient.PullRequestResult pr = client.createPullRequest(
                title, description, sourceBranch, destBranch);
        return ToolResult.success(toolUseId, "PR created: %s\nURL: %s", pr.id(), pr.url());
    }

    private String resolveBranch() throws Exception {
        if (defaultBranch != null)
            return defaultBranch;
        return client.getDefaultBranch();
    }
}
