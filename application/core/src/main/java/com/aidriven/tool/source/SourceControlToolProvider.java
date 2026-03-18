package com.aidriven.tool.source;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.source.RepositoryReader;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.ast.AstParser;
import com.aidriven.core.ast.CodeNode;
import com.aidriven.core.ast.java.JavaAstParser;
import com.aidriven.spi.model.BranchName;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.core.source.RepositoryWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Exposes {@link SourceControlClient} operations as Claude tools.
 */
@Slf4j
public class SourceControlToolProvider implements ToolProvider {

    private final SourceControlClient client;
    private final String defaultBranch;
    private final AstParser astParser;
    private final Cache<String, String> outlineCache;

    public SourceControlToolProvider(SourceControlClient client) {
        this(client, null);
    }

    public SourceControlToolProvider(SourceControlClient client, String defaultBranch) {
        this.client = client;
        this.defaultBranch = defaultBranch;
        this.astParser = new JavaAstParser();
        this.outlineCache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(200)
                .build();
    }

    @Override
    public String namespace() {
        return "source_control";
    }

    @Override
    public List<Tool> toolDefinitions() {
        return List.of(
                Tool.of("source_control_get_file", "Read file content.",
                        Map.of("branch", Tool.stringProp("Branch"), "file_path", Tool.stringProp("Path")), "file_path"),
                Tool.of("source_control_search_files", "Search files.",
                        Map.of("query", Tool.stringProp("Query")), "query"),
                Tool.of("source_control_get_file_tree", "List files.",
                        Map.of("branch", Tool.stringProp("Branch"), "path", Tool.stringProp("Path")), "branch"),
                Tool.of("source_control_create_branch", "Create branch.",
                        Map.of("branch_name", Tool.stringProp("Name"), "from_branch", Tool.stringProp("From")),
                        "branch_name"),
                Tool.of("source_control_commit_files", "Commit changes.",
                        Map.of("branch_name", Tool.stringProp("Branch"), "commit_message", Tool.stringProp("Msg"),
                                "files",
                                Tool.objectArrayProp("Files",
                                        Map.of("path", Tool.stringProp("Path"), "content", Tool.stringProp("Content"),
                                                "operation", Tool.stringProp("Op")))),
                        "branch_name", "commit_message", "files"),
                Tool.of("source_control_create_pr", "Create PR.",
                        Map.of("title", Tool.stringProp("Title"), "description", Tool.stringProp("Desc"),
                                "source_branch", Tool.stringProp("Src"), "destination_branch", Tool.stringProp("Dest")),
                        "title", "source_branch"),
                Tool.of("source_control_list_pull_requests", "List open Pull Requests.",
                        new HashMap<String, Object>()),
                Tool.of("source_control_get_ci_logs", "Fetch CI workflow logs for a given run ID.",
                        Map.of("run_id", Tool.stringProp("Run ID to fetch logs for"),
                                "max_log_chars",
                                Tool.intProp("Max characters to return, defaults to ~100KB if omitted")),
                        "run_id"),
                Tool.of("source_control_view_file_outline",
                        "View an AST-based structural outline of a file (class/method skeletons, no bodies). " +
                                "For Java files, returns class and method signatures. For other file types, returns the first 2000 characters. "
                                +
                                "Prefer this over get_file when you need to understand structure of a large file.",
                        Map.of("file_path", Tool.stringProp("File path to outline"),
                                "branch", Tool.stringProp("Branch (optional, defaults to main)"),
                                "max_depth", Tool.intProp("0=class only, 1=class+methods (default), 2=include fields")),
                        "file_path"),
                Tool.of("source_control_search_grep",
                        "Search file contents using a regex or plain text query across files returned by search_files. "
                                +
                                "Returns matching lines with context. Useful for finding specific usages or patterns.",
                        Map.of("query", Tool.stringProp("Search query or pattern"),
                                "file_path", Tool.stringProp("Specific file to search within (optional)"),
                                "branch", Tool.stringProp("Branch (optional)"),
                                "is_regex", Tool.stringProp("Set to 'true' to treat query as a regex pattern")),
                        "query"));
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();
        try {
            return switch (action) {
                case "get_file" -> getFile(context, call.id(), input);
                case "search_files" -> searchFiles(context, call.id(), input);
                case "get_file_tree" -> getFileTree(context, call.id(), input);
                case "create_branch" -> createBranch(context, call.id(), input);
                case "commit_files" -> commitFiles(context, call.id(), input);
                case "create_pr" -> createPr(context, call.id(), input);
                case "list_pull_requests" -> listPullRequests(context, call.id());
                case "get_ci_logs" -> getCiLogs(context, call.id(), input);
                case "view_file_outline" -> viewFileOutline(context, call.id(), input);
                case "search_grep" -> searchCodeGrep(context, call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown action");
            };
        } catch (com.aidriven.core.exception.HttpClientException e) {
            log.error("Tool HTTP error: {} (HTTP {})", e.getMessage(), e.getStatusCode());
            return ToolResult.error(call.id(), "HTTP Error " + e.getStatusCode() + ": " + e.getMessage());
        } catch (java.io.IOException e) {
            log.error("Tool I/O error: {}", e.getMessage());
            return ToolResult.error(call.id(), "I/O Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Tool execution interrupted: {}", e.getMessage());
            return ToolResult.error(call.id(), "Error: operation interrupted");
        } catch (RuntimeException e) {
            log.error("Tool error: {}", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Tool error: {}", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    private ToolResult getFile(OperationContext context, String id, JsonNode input) throws Exception {
        BranchName branch = input.has("branch") ? BranchName.of(input.get("branch").asText()) : resolveBranch(context);
        String path = input.get("file_path").asText();
        String content = client.getFileContent(context, branch, path);
        return content != null ? ToolResult.success(id, content) : ToolResult.error(id, "not found");
    }

    private ToolResult searchFiles(OperationContext context, String id, JsonNode input) throws Exception {
        String query = input.get("query").asText();
        List<String> results = client.searchFiles(context, query);
        return ToolResult.success(id, "Found " + results.size() + " files:\n" + String.join("\n", results));
    }

    private ToolResult getFileTree(OperationContext context, String id, JsonNode input) throws Exception {
        BranchName branch = input.has("branch") ? BranchName.of(input.get("branch").asText()) : resolveBranch(context);
        String path = input.has("path") ? input.get("path").asText() : null;
        List<String> files = client.getFileTree(context, branch, path);
        return ToolResult.success(id, "Tree:\n" + String.join("\n", files));
    }

    private ToolResult createBranch(OperationContext context, String id, JsonNode input) throws Exception {
        BranchName name = BranchName.of(input.get("branch_name").asText());
        BranchName from = input.has("from_branch") ? BranchName.of(input.get("from_branch").asText())
                : resolveBranch(context);
        client.createBranch(context, name, from);
        return ToolResult.success(id, "Created branch " + name.name());
    }

    private ToolResult commitFiles(OperationContext context, String id, JsonNode input) throws Exception {
        BranchName branch = BranchName.of(input.get("branch_name").asText());
        String msg = input.get("commit_message").asText();
        List<AgentResult.GeneratedFile> files = new ArrayList<>();
        for (JsonNode f : input.get("files")) {
            files.add(new AgentResult.GeneratedFile(f.get("path").asText(), f.get("content").asText(),
                    AgentResult.FileOperation.valueOf(f.path("operation").asText("CREATE"))));
        }
        String commitId = client.commitFiles(context, branch, files, msg);
        return ToolResult.success(id, "Committed: " + commitId);
    }

    private ToolResult createPr(OperationContext context, String id, JsonNode input) throws Exception {
        String title = input.get("title").asText();
        String description = input.path("description").asText("");
        String sourceBranch = input.get("source_branch").asText();
        String destinationBranch = input.has("destination_branch") ? input.get("destination_branch").asText()
                : resolveBranch(context).name();

        SourceControlProvider.PullRequestResult result = client.createPullRequest(context, title,
                description, BranchName.of(sourceBranch), BranchName.of(destinationBranch));
        return ToolResult.success(id, "PR: " + result.url());
    }

    private ToolResult listPullRequests(OperationContext context, String id) throws Exception {
        List<RepositoryReader.PullRequestSummary> prs = client.listPullRequests(context);
        StringBuilder sb = new StringBuilder("Open Pull Requests:\n");
        for (var pr : prs) {
            sb.append(
                    String.format("- #%s: %s (Branch: %s) - %s\n", pr.id(), pr.title(), pr.branch().name(),
                            pr.url()));
        }
        return ToolResult.success(id, sb.toString());
    }

    private ToolResult getCiLogs(OperationContext context, String id, JsonNode input) throws Exception {
        String runId = input.get("run_id").asText();
        Integer maxChars = input.has("max_log_chars") && !input.get("max_log_chars").isNull()
                ? input.get("max_log_chars").asInt()
                : null;
        String logs = client.getWorkflowRunLogs(context, runId, maxChars);
        return ToolResult.success(id, logs);
    }

    private BranchName resolveBranch(OperationContext context) throws Exception {
        return defaultBranch != null ? BranchName.of(defaultBranch) : client.getDefaultBranch(context);
    }

    private ToolResult viewFileOutline(OperationContext context, String id, JsonNode input) throws Exception {
        BranchName branch = input.has("branch") ? BranchName.of(input.get("branch").asText()) : resolveBranch(context);
        String path = input.get("file_path").asText();
        int maxDepth = input.has("max_depth") && !input.get("max_depth").isNull()
                ? input.get("max_depth").asInt()
                : 1;

        String cacheKey = context.tenantId() + ":" + branch.name() + ":" + path + ":" + maxDepth;
        String cached = outlineCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("AST outline cache hit: {}", path);
            return ToolResult.success(id, cached);
        }

        String content = client.getFileContent(context, branch, path);
        if (content == null) {
            return ToolResult.error(id, "File not found: " + path);
        }

        List<CodeNode> nodes = astParser.parse(path, content, maxDepth);
        String outline = renderOutline(path, nodes);
        outlineCache.put(cacheKey, outline);
        return ToolResult.success(id, outline);
    }

    private ToolResult searchCodeGrep(OperationContext context, String id, JsonNode input) throws Exception {
        String query = input.get("query").asText();
        boolean isRegex = "true".equalsIgnoreCase(input.path("is_regex").asText(""));

        List<String> filesToSearch;
        if (input.has("file_path") && !input.get("file_path").isNull()) {
            filesToSearch = List.of(input.get("file_path").asText());
        } else {
            // Search by file name across repo, then grep inside
            filesToSearch = client.searchFiles(context, query);
            if (filesToSearch.isEmpty()) {
                return ToolResult.success(id, "No files found matching: " + query);
            }
        }

        BranchName branch = input.has("branch") ? BranchName.of(input.get("branch").asText()) : resolveBranch(context);
        StringBuilder sb = new StringBuilder();
        int matchCount = 0;

        for (String filePath : filesToSearch.subList(0, Math.min(filesToSearch.size(), 10))) {
            String content = client.getFileContent(context, branch, filePath);
            if (content == null)
                continue;

            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                boolean matched = isRegex ? lines[i].matches(".*" + query + ".*") : lines[i].contains(query);
                if (matched) {
                    sb.append(filePath).append(":").append(i + 1).append(": ").append(lines[i].strip()).append('\n');
                    matchCount++;
                    if (matchCount >= 50)
                        break;
                }
            }
            if (matchCount >= 50)
                break;
        }

        return sb.isEmpty()
                ? ToolResult.success(id, "No matches found for: " + query)
                : ToolResult.success(id, matchCount + " match(es):\n" + sb);
    }

    private String renderOutline(String path, List<CodeNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## File Outline: ").append(path).append("\n\n");
        for (CodeNode node : nodes) {
            if ("RAW_TEXT".equals(node.getType())) {
                sb.append("*Non-Java/unsupported format. First 2000 chars:*\n\n");
                if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                    sb.append("```\n").append(node.getChildren().get(0).getSignature()).append("\n```\n");
                }
            } else {
                sb.append(node.getType().toLowerCase()).append(' ').append(node.getSignature()).append(" {\n");
                if (node.getChildren() != null) {
                    for (CodeNode child : node.getChildren()) {
                        sb.append("  ").append(child.getSignature()).append("\n");
                    }
                }
                sb.append("}\n\n");
            }
        }
        return sb.toString();
    }
}
