package com.aidriven.github;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.exception.ConfigurationException;
import com.aidriven.core.exception.HttpClientException;
import com.aidriven.core.service.SecretsService;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.core.source.RepositoryReader;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.util.HttpResponseHandler;
import com.aidriven.core.resilience.CircuitBreaker;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.spi.provider.SourceControlProvider.RepoFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with GitHub REST API.
 * Implements both SourceControlClient (internal) and SourceControlProvider
 * (SPI).
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class GitHubClient implements SourceControlClient, SourceControlProvider {

    private static final String API_BASE = "https://api.github.com";
    private static final String GITHUB_API_HEADER = "application/vnd.github+json";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String AUTH_HEADER = "Authorization";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String REPOS_ENDPOINT = "/repos/";
    private static final String GIT_ENDPOINT = "/git/";
    private static final String BLOB_TYPE = "blob";
    private static final String MODE_100644 = "100644";
    private static final String DEFAULT_BRANCH_FIELD = "default_branch";
    private static final String REFS_HEADS = "refs/heads/";
    private static final String OPERATION_GITHUB = "GitHub";
    private static final int HTTP_STATUS_OK = 200;
    private static final int HTTP_STATUS_NOT_FOUND = 404;
    private static final int HTTP_STATUS_UNPROCESSABLE = 422;

    private final @NonNull String owner;
    private final @NonNull String repo;
    private final @NonNull String authHeader;
    private final @NonNull HttpClient httpClient;
    private final @NonNull ObjectMapper objectMapper;
    private CircuitBreaker circuitBreaker;

    /** Configuration POJO for GitHub */
    public record GitHubSecret(String owner, String repo, String token) {
    }

    /** Public constructor for direct instantiation with a token. */
    public GitHubClient(String owner, String repo, String token) {
        this.owner = requireNonEmpty(owner, "owner");
        this.repo = requireNonEmpty(repo, "repo");
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        this.authHeader = "Bearer " + requireNonEmpty(token, "token");
    }

    /**
     * Creates a GitHubClient from an AWS Secrets Manager secret.
     */
    public static GitHubClient fromSecrets(SecretsService secretsManager, String secretArn) {
        try {
            GitHubSecret secret = secretsManager.getSecretAs(secretArn, GitHubSecret.class);
            if (secret == null || secret.owner() == null || secret.repo() == null || secret.token() == null) {
                throw new ConfigurationException(
                        "GitHubClient: secret '" + secretArn + "' is missing required fields (owner, repo, token)");
            }
            return new GitHubClient(secret.owner(), secret.repo(), secret.token());
        } catch (ConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigurationException(
                    "GitHubClient: failed to load credentials from secret '" + secretArn + "'", e);
        }
    }

    /**
     * Returns a new client scoped to a specific repository, reusing the same HTTP
     * connection pool.
     */
    public GitHubClient withRepository(String owner, String repo) {
        GitHubClient client = new GitHubClient(
                requireNonEmpty(owner, "owner"),
                requireNonEmpty(repo, "repo"),
                authHeader, httpClient, objectMapper);
        client.circuitBreaker = this.circuitBreaker;
        return client;
    }

    public GitHubClient withCircuitBreaker(CircuitBreaker breaker) {
        this.circuitBreaker = breaker;
        return this;
    }

    @Override
    public String getName() {
        return "github";
    }

    @Override
    public boolean supports(String repositoryUri) {
        return repositoryUri != null && repositoryUri.toLowerCase().contains("github.com")
                && repositoryUri.contains("/" + owner + "/" + repo);
    }

    // --- RepositoryReader / SourceControlClient / SourceControlProvider ---

    @Override
    public BranchName getDefaultBranch(OperationContext context) throws Exception {
        String url = buildRepoUrl();
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "getDefaultBranch",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "getDefaultBranch");
        return BranchName.of(objectMapper.readTree(response.body()).get(DEFAULT_BRANCH_FIELD).asText());
    }

    @Override
    public void createBranch(OperationContext context, BranchName branchName, BranchName fromBranch) throws Exception {
        String baseSha = getBranchSha(fromBranch.name());
        String url = buildRepoUrl() + GIT_ENDPOINT + "refs";
        String body = objectMapper
                .writeValueAsString(Map.of("ref", REFS_HEADS + branchName.name(), "sha", baseSha));
        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB, "createBranch",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "createBranch " + branchName.name());
    }

    @Override
    public String pushFiles(OperationContext context, BranchName branchName, List<RepoFile> files, String commitMessage)
            throws Exception {
        log.info("Committing {} files to branch {} via GitHub API", files.size(), branchName.name());
        String baseSha = getBranchSha(branchName.name());
        String baseTreeSha = getCommitTreeSha(baseSha);

        String newTreeSha = createTreeFromRepoFiles(baseTreeSha, files);
        String safeMessage = commitMessage != null ? commitMessage : "Auto-generated commit";
        String commitSha = createCommit(safeMessage, newTreeSha, baseSha);
        updateRef(branchName.name(), commitSha);
        return commitSha;
    }

    @Override
    public String commitFiles(OperationContext context, BranchName branchName,
            List<AgentResult.GeneratedFile> files, String commitMessage) throws Exception {
        List<RepoFile> repoFiles = files.stream()
                .map(f -> new RepoFile(f.getPath(), f.getContent(), f.getOperation().name()))
                .toList();
        return pushFiles(context, branchName, repoFiles, commitMessage);
    }

    @Override
    public SourceControlProvider.PullRequestResult openPullRequest(OperationContext context, String title,
            String description,
            BranchName sourceBranch, BranchName destinationBranch) throws Exception {
        String url = String.format("%s/repos/%s/%s/pulls", API_BASE, owner, repo);
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("title", title);
        bodyMap.put("body", description);
        bodyMap.put("head", sourceBranch.name());
        bodyMap.put("base", destinationBranch.name());
        String body = objectMapper.writeValueAsString(bodyMap);
        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "openPullRequest",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "openPullRequest");
        JsonNode json = objectMapper.readTree(response.body());
        return new SourceControlProvider.PullRequestResult(json.get("number").toString(),
                json.get("html_url").asText(), sourceBranch, title);
    }

    @Override
    public SourceControlProvider.PullRequestResult createPullRequest(OperationContext context, String title,
            String description, BranchName sourceBranch, BranchName destinationBranch)
            throws Exception {
        return openPullRequest(context, title, description, sourceBranch, destinationBranch);
    }

    @Override
    public List<RepositoryReader.PullRequestSummary> listPullRequests(OperationContext context) throws Exception {
        String url = String.format("%s/repos/%s/%s/pulls?state=open", API_BASE, owner, repo);
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "listPullRequests", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "listPullRequests");

        JsonNode json = objectMapper.readTree(response.body());
        List<RepositoryReader.PullRequestSummary> results = new ArrayList<>();
        if (json.isArray()) {
            for (JsonNode pr : json) {
                results.add(new RepositoryReader.PullRequestSummary(
                        pr.path("number").toString(),
                        pr.path("html_url").asText(),
                        BranchName.of(pr.path("head").path("ref").asText()),
                        pr.path("title").asText()));
            }
        }
        return results;
    }

    private String buildRepoUrl() {
        return API_BASE + REPOS_ENDPOINT + owner + "/" + repo;
    }

    @Override
    public List<String> getFileTree(OperationContext context, @NonNull BranchName branch, String path)
            throws Exception {
        String url = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1", API_BASE, encode(owner), encode(repo),
                branch.name());
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB, "getFileTree",
                circuitBreaker);
        if (response.statusCode() != HTTP_STATUS_OK) {
            return List.of();
        }
        JsonNode tree = objectMapper.readTree(response.body()).get("tree");
        return extractBlobFiles(tree, path);
    }

    private List<String> extractBlobFiles(JsonNode tree, String pathFilter) {
        List<String> files = new ArrayList<>();
        if (tree == null || !tree.isArray()) {
            return files;
        }
        for (JsonNode node : tree) {
            if (BLOB_TYPE.equals(node.path("type").asText())) {
                String nodePath = node.path("path").asText();
                if (pathFilter == null || nodePath.startsWith(pathFilter)) {
                    files.add(nodePath);
                }
            }
        }
        return files;
    }

    @Override
    public String getFileContent(OperationContext context, @NonNull BranchName branch, String filePath)
            throws Exception {
        String url = String.format("%s/repos/%s/%s/contents/%s?ref=%s", API_BASE, encode(owner), encode(repo),
                encode(filePath), branch.name());
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "getFileContent",
                circuitBreaker);
        if (response.statusCode() == HTTP_STATUS_NOT_FOUND)
            return null;
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "getFileContent");
        JsonNode json = objectMapper.readTree(response.body());
        String content = json.get("content").asText().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> searchFiles(OperationContext context, String query) throws Exception {
        String url = String.format("%s/search/code?q=repo:%s/%s+%s", API_BASE, encode(owner), encode(repo),
                encode(query));
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB, "searchFiles",
                circuitBreaker);
        try {
            HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "searchFiles");
            JsonNode json = objectMapper.readTree(response.body());
            return extractSearchResults(json);
        } catch (HttpClientException e) {
            log.warn("searchFiles failed for query {}: {}. Returning empty results.", query, e.getMessage());
            return List.of();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("searchFiles failed for query {}: {}. Returning empty results.", query, e.getMessage());
            return List.of();
        }
    }

    private List<String> extractSearchResults(JsonNode json) {
        if (!json.has("items")) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode item : json.get("items")) {
            JsonNode pathNode = item.get("path");
            if (pathNode != null) {
                paths.add(pathNode.asText());
            }
        }
        return paths;
    }

    @Override
    public String addPrComment(OperationContext context, String prNumber, String body) throws Exception {
        String url = String.format("%s/repos/%s/%s/issues/%s/comments", API_BASE, owner, repo, prNumber);
        HttpRequest request = buildPostRequest(url, objectMapper.writeValueAsString(Map.of("body", body)));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB, "addPrComment",
                circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "addPrComment");
        return objectMapper.readTree(response.body()).get("id").toString();
    }

    @Override
    public void addPrComment(OperationContext context, String repoOwner, String repoSlug, String prNumber,
            String body) {
        try {
            addPrComment(context, prNumber, body);
        } catch (HttpClientException e) {
            log.error("Failed to add PR comment: HTTP {} - {}", e.getStatusCode(), e.getMessage(), e);
        } catch (java.io.IOException e) {
            log.error("Failed to add PR comment: I/O error: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to add PR comment: interrupted", e);
        } catch (Exception e) {
            log.error("Failed to add PR comment: {}", e.getMessage(), e);
        }
    }

    @Override
    public String addPrCommentReply(OperationContext context, String prNumber, String parentCommentId, String body)
            throws Exception {
        return addPrCommentReplyInternal(context, owner, repo, prNumber, parentCommentId, body);
    }

    private String addPrCommentReplyInternal(OperationContext context, String repoOwner, String repoSlug,
            String prNumber, String parentCommentId, String body) throws Exception {
        String url = String.format("%s/repos/%s/%s/pulls/%s/comments", API_BASE, encode(repoOwner),
                encode(repoSlug), prNumber);
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("body", body);
        try {
            long parentId = Long.parseLong(parentCommentId);
            bodyMap.put("in_reply_to_id", parentId);
        } catch (NumberFormatException e) {
            return addPrComment(context, prNumber, body);
        }

        HttpRequest request = buildPostRequest(url, objectMapper.writeValueAsString(bodyMap));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "addPrCommentReply", circuitBreaker);

        if (response.statusCode() == HTTP_STATUS_UNPROCESSABLE || response.statusCode() == HTTP_STATUS_NOT_FOUND) {
            return addPrComment(context, prNumber, body);
        }

        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "addPrCommentReply");
        return objectMapper.readTree(response.body()).get("id").toString();
    }

    @Override
    public void addPrCommentReply(OperationContext context, String repoOwner, String repoSlug, String prNumber,
            String parentCommentId, String body) {
        try {
            addPrCommentReplyInternal(context, repoOwner, repoSlug, prNumber, parentCommentId, body);
        } catch (HttpClientException e) {
            log.error("Failed to add PR comment reply: HTTP {} - {}", e.getStatusCode(), e.getMessage(), e);
        } catch (java.io.IOException e) {
            log.error("Failed to add PR comment reply: I/O error: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to add PR comment reply: interrupted", e);
        } catch (Exception e) {
            log.error("Failed to add PR comment reply: {}", e.getMessage(), e);
        }
    }

    @Override
    public Path downloadArchive(OperationContext context, @NonNull BranchName branch, Path outputDir) throws Exception {
        String url = String.format("%s/repos/%s/%s/zipball/%s", API_BASE, encode(owner), encode(repo), branch.name());
        HttpRequest request = buildGetRequest(url);
        HttpResponse<byte[]> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()), OPERATION_GITHUB,
                "downloadArchive", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "downloadArchive");

        Files.createDirectories(outputDir);
        Path zipFile = outputDir.resolve("repo.zip");
        Files.write(zipFile, response.body());

        try {
            unzip(zipFile, outputDir);
            return findRepoRoot(outputDir);
        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    @Override
    public String getWorkflowRunLogs(OperationContext context, String runId, Integer maxLogChars) throws Exception {
        String url = String.format("%s/repos/%s/%s/actions/runs/%s/logs", API_BASE, encode(owner), encode(repo),
                encode(runId));
        HttpRequest request = buildGetRequest(url);

        HttpResponse<byte[]> response = null;
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            response = HttpResponseHandler.sendWithCircuitBreaker(
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()), OPERATION_GITHUB,
                    "getWorkflowRunLogs", circuitBreaker);
            if (response.statusCode() >= 500 && i < retries - 1) {
                Thread.sleep(1000L * (i + 1));
                continue;
            }
            break;
        }

        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "getWorkflowRunLogs");

        int limit = (maxLogChars != null) ? maxLogChars : 100_000;
        StringBuilder logsBuilder = new StringBuilder();

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(response.body()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".txt")) {
                    logsBuilder.append("--- Log File: ").append(entry.getName()).append(" ---\n");
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        logsBuilder.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    }
                    logsBuilder.append("\n");
                }
                zis.closeEntry();
            }
        }

        String allLogs = logsBuilder.toString();
        if (allLogs.length() > limit) {
            int headLen = limit / 2;
            int tailLen = limit - headLen;
            return allLogs.substring(0, headLen) + "\n... [LOGS TRUNCATED] ...\n"
                    + allLogs.substring(allLogs.length() - tailLen);
        }

        return allLogs;
    }

    private Path findRepoRoot(Path extractDir) throws IOException {
        try (var stream = Files.list(extractDir)) {
            return stream.filter(Files::isDirectory)
                    .findFirst()
                    .orElse(extractDir);
        }
    }

    private void unzip(Path zipFile, Path extractDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = extractDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    Files.copy(zis, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // --- Private Helpers ---

    private String getBranchSha(String branch) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/ref/heads/%s", API_BASE, owner, repo, branch);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "getBranchSha", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "getBranchSha");
        return objectMapper.readTree(response.body()).get("object").get("sha").asText();
    }

    private String getCommitTreeSha(String commitSha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/commits/%s", API_BASE, owner, repo, commitSha);
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString()), OPERATION_GITHUB,
                "getCommitTreeSha", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "getCommitTreeSha");
        return objectMapper.readTree(response.body()).get("tree").get("sha").asText();
    }

    private String createTreeFromRepoFiles(String baseTreeSha, List<RepoFile> files) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/trees", API_BASE, owner, repo);
        List<Map<String, Object>> tree = files.stream()
                .map(f -> Map.of(
                        "path", (Object) f.path(),
                        "mode", MODE_100644,
                        "type", BLOB_TYPE,
                        "content", f.content()))
                .toList();
        String body = objectMapper.writeValueAsString(Map.of("base_tree", baseTreeSha, "tree", tree));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildPostRequest(url, body), HttpResponse.BodyHandlers.ofString()),
                OPERATION_GITHUB, "createTree", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "createTree");
        return objectMapper.readTree(response.body()).get("sha").asText();
    }

    private String createCommit(String msg, String treeSha, String parentSha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/commits", API_BASE, owner, repo);
        String body = objectMapper
                .writeValueAsString(Map.of("message", msg, "tree", treeSha, "parents", List.of(parentSha)));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildPostRequest(url, body), HttpResponse.BodyHandlers.ofString()),
                OPERATION_GITHUB, "createCommit", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "createCommit");
        return objectMapper.readTree(response.body()).get("sha").asText();
    }

    private void updateRef(String branch, String sha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/refs/heads/%s", API_BASE, owner, repo, branch);
        String body = objectMapper.writeValueAsString(Map.of("sha", sha, "force", true));
        HttpResponse<String> response = HttpResponseHandler.sendWithCircuitBreaker(
                () -> httpClient.send(buildPatchRequest(url, body), HttpResponse.BodyHandlers.ofString()),
                OPERATION_GITHUB, "updateRef", circuitBreaker);
        HttpResponseHandler.checkResponse(response, OPERATION_GITHUB, "updateRef");
    }

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header(AUTH_HEADER, authHeader)
                .header(ACCEPT_HEADER, GITHUB_API_HEADER).GET().build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header(AUTH_HEADER, authHeader)
                .header("Content-Type", CONTENT_TYPE_JSON).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest buildPatchRequest(String url, String body) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header(AUTH_HEADER, authHeader)
                .header("Content-Type", CONTENT_TYPE_JSON).method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    private static String requireNonEmpty(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("GitHubClient: '" + fieldName + "' must not be null or blank");
        }
        return value;
    }

    public static ParsedRepoUrl parseRepoUrl(String url) {
        if (url == null)
            throw new NullPointerException("URL cannot be null");
        String trimmed = url.trim();
        if (lowerMatch(trimmed, "api.github.com" + REPOS_ENDPOINT)) {
            int start = trimmed.toLowerCase().indexOf(REPOS_ENDPOINT) + REPOS_ENDPOINT.length();
            String path = strippedPath(trimmed.substring(start));
            String[] parts = path.split("/");
            if (parts.length >= 2)
                return new ParsedRepoUrl(parts[0], parts[1]);
        }
        if (lowerMatch(trimmed, "git@github.com:")) {
            int start = trimmed.toLowerCase().indexOf("github.com:") + 11;
            String path = strippedPath(trimmed.substring(start));
            String[] parts = path.split("/");
            if (parts.length >= 2)
                return new ParsedRepoUrl(parts[0], parts[1]);
        }
        if (lowerMatch(trimmed, "github.com/")) {
            int start = trimmed.toLowerCase().indexOf("github.com/") + 11;
            String path = strippedPath(trimmed.substring(start));
            String[] parts = path.split("/");
            if (parts.length >= 2)
                return new ParsedRepoUrl(parts[0], parts[1]);
        }
        throw new IllegalArgumentException("Unrecognized GitHub URL: " + url);
    }

    private static boolean lowerMatch(String s, String part) {
        return s.toLowerCase().contains(part);
    }

    private static String strippedPath(String p) {
        return p.replace(".git", "");
    }

    public record ParsedRepoUrl(String owner, String repo) {
    }
}
