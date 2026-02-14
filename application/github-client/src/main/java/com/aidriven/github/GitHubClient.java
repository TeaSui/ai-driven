package com.aidriven.github;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.util.HttpResponseHandler;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Client for interacting with GitHub REST API v3.
 * Implements {@link SourceControlClient} for platform-agnostic source control
 * operations.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class GitHubClient implements SourceControlClient {

    private static final String API_BASE = "https://api.github.com";

    private final @NonNull String authHeader;
    private final @NonNull HttpClient httpClient;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull String owner;
    private final @NonNull String repo;

    /**
     * Creates a GitHubClient from Secrets Manager.
     * Expected secret keys: owner, repo, token
     */
    public static GitHubClient fromSecrets(SecretsService secretsService, String secretArn) {
        try {
            Map<String, Object> secrets = secretsService.getSecretJson(secretArn);

            String owner = getRequiredSecret(secrets, "owner");
            String repo = getRequiredSecret(secrets, "repo");
            String token = getRequiredSecret(secrets, "token");

            String authHeader = "Bearer " + token;

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            ObjectMapper objectMapper = new ObjectMapper();

            return new GitHubClient(authHeader, httpClient, objectMapper, owner, repo);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create GitHubClient from secrets", e);
        }
    }

    /**
     * Creates a GitHubClient from a repository URL and token.
     */
    public static GitHubClient fromRepoUrl(String repoUrl, String token) {
        ParsedRepoUrl parsed = parseRepoUrl(repoUrl);
        String authHeader = "Bearer " + token;

        return new GitHubClient(
                authHeader,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                parsed.owner(),
                parsed.repo());
    }

    /**
     * Returns a new GitHubClient instance for a different repository using the same
     * credentials.
     */
    public GitHubClient withRepository(@NonNull String owner, @NonNull String repo) {
        return new GitHubClient(authHeader, httpClient, objectMapper, owner, repo);
    }

    private static String getRequiredSecret(Map<String, Object> secrets, String key) {
        Object val = secrets.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required key '" + key + "' in GitHub secret");
        }
        return val.toString();
    }

    @Override
    public String getDefaultBranch() throws Exception {
        String url = String.format("%s/repos/%s/%s", API_BASE, encode(owner), encode(repo));

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "getDefaultBranch");

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("default_branch").asText();
    }

    @Override
    public void createBranch(String branchName, String fromBranch) throws Exception {
        // Get the SHA of the source branch
        String sha = getBranchSha(fromBranch);

        String url = String.format("%s/repos/%s/%s/git/refs", API_BASE, encode(owner), encode(repo));

        String body = objectMapper.writeValueAsString(Map.of(
                "ref", "refs/heads/" + branchName,
                "sha", sha));

        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "GitHub", "createBranch " + branchName);
        }

        log.info("Created branch: {} from {} ({})", branchName, fromBranch, sha);
    }

    @Override
    public String commitFiles(String branchName, List<AgentResult.GeneratedFile> files, String commitMessage)
            throws Exception {
        // 1. Get the current commit SHA of the branch
        String branchSha = getBranchSha(branchName);

        // 2. Get the tree SHA of the current commit
        String treeSha = getCommitTreeSha(branchSha);

        // 3. Create a new tree with the files
        String newTreeSha = createTree(treeSha, files);

        // 4. Create a new commit
        String safeMessage = commitMessage != null ? commitMessage : "Auto-generated commit";
        String newCommitSha = createCommit(safeMessage, newTreeSha, branchSha);

        // 5. Update the branch reference to point to the new commit
        updateRef(branchName, newCommitSha);

        log.info("Committed {} files to branch {}", files.size(), branchName);
        return newCommitSha;
    }

    @Override
    public PullRequestResult createPullRequest(String title, String description,
            String sourceBranch, String destinationBranch) throws Exception {
        String url = String.format("%s/repos/%s/%s/pulls", API_BASE, encode(owner), encode(repo));

        var prMap = new java.util.HashMap<String, Object>();
        prMap.put("title", title);
        prMap.put("body", description != null ? description : "");
        prMap.put("head", sourceBranch);
        prMap.put("base", destinationBranch);
        String body = objectMapper.writeValueAsString(prMap);

        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "GitHub",
                    "createPullRequest " + sourceBranch + " -> " + destinationBranch);
        }

        JsonNode json = objectMapper.readTree(response.body());
        String prNumber = String.valueOf(json.get("number").asInt());
        String prUrl = json.get("html_url").asText();

        log.info("Created pull request: {} -> {}", prUrl, prNumber);

        return new PullRequestResult(prNumber, prUrl, sourceBranch);
    }

    @Override
    public Path downloadArchive(String branch, Path outputDir) throws Exception {
        String url = String.format("%s/repos/%s/%s/zipball/%s",
                API_BASE, encode(owner), encode(repo), encode(branch));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        Path zipFile = outputDir.resolve("repo-" + System.currentTimeMillis() + ".zip");
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(zipFile));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(zipFile);
            throw new RuntimeException("Failed to download archive: HTTP " + response.statusCode());
        }

        log.info("Downloaded archive: {} ({} bytes)", zipFile, Files.size(zipFile));

        // Extract zip to a temp directory
        Path extractDir = outputDir.resolve("repo-extract-" + System.currentTimeMillis());
        Files.createDirectories(extractDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = extractDir.resolve(entry.getName()).normalize();

                // Prevent zip-slip attacks
                if (!entryPath.startsWith(extractDir)) {
                    throw new SecurityException("Zip entry outside target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath);
                }
                zis.closeEntry();
            }
        }

        Files.deleteIfExists(zipFile);

        // GitHub zips contain a single root directory: {owner}-{repo}-{hash}/
        try (var dirs = Files.list(extractDir)) {
            List<Path> topLevel = dirs.filter(Files::isDirectory).toList();
            if (topLevel.size() == 1) {
                log.info("Extracted repo to: {}", topLevel.get(0));
                return topLevel.get(0);
            }
        }

        log.info("Extracted repo to: {}", extractDir);
        return extractDir;
    }

    @Override
    public List<String> getFileTree(String branch, String path) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1",
                API_BASE, encode(owner), encode(repo), encode(branch));

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Failed to get file tree: HTTP {}", response.statusCode());
            return List.of();
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode tree = json.get("tree");

        List<String> files = new ArrayList<>();
        if (tree != null && tree.isArray()) {
            for (JsonNode node : tree) {
                if ("blob".equals(node.get("type").asText())) {
                    String filePath = node.get("path").asText();
                    if (path == null || path.isEmpty() || filePath.startsWith(path)) {
                        files.add(filePath);
                    }
                }
            }
        }

        return files;
    }

    @Override
    public String getFileContent(String branch, String filePath) throws Exception {
        String url = String.format("%s/repos/%s/%s/contents/%s?ref=%s",
                API_BASE, encode(owner), encode(repo), filePath, encode(branch));

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Failed to get file content for {}: HTTP {}", filePath, response.statusCode());
            return null;
        }

        JsonNode json = objectMapper.readTree(response.body());
        String encoding = json.has("encoding") ? json.get("encoding").asText() : "";

        if ("base64".equals(encoding)) {
            String content = json.get("content").asText().replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        }

        return json.has("content") ? json.get("content").asText() : null;
    }

    @Override
    public List<String> searchFiles(String query) throws Exception {
        String searchQuery = String.format("%s+repo:%s/%s", encode(query), encode(owner), encode(repo));
        String url = String.format("%s/search/code?q=%s", API_BASE, searchQuery);

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Code search failed: HTTP {}", response.statusCode());
            return List.of();
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode items = json.get("items");

        List<String> files = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                files.add(item.get("path").asText());
            }
        }
        return files;
    }

    // ==================== HELPER METHODS ====================

    private String getBranchSha(String branchName) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/ref/heads/%s",
                API_BASE, encode(owner), encode(repo), encode(branchName));

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "getBranchSha " + branchName);

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("object").get("sha").asText();
    }

    private String getCommitTreeSha(String commitSha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/commits/%s",
                API_BASE, encode(owner), encode(repo), commitSha);

        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "getCommitTreeSha " + commitSha);

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("tree").get("sha").asText();
    }

    private String createTree(String baseTreeSha, List<AgentResult.GeneratedFile> files) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/trees", API_BASE, encode(owner), encode(repo));

        List<Map<String, String>> treeEntries = new ArrayList<>();
        for (AgentResult.GeneratedFile file : files) {
            String safePath = file.getPath().replaceAll("^[./]*", "").replace("..", "");
            String mode = "100644"; // regular file

            if (file.getOperation() == AgentResult.FileOperation.DELETE) {
                // For deletions, we'd need a different approach (omit from tree)
                continue;
            }

            treeEntries.add(Map.of(
                    "path", safePath,
                    "mode", mode,
                    "type", "blob",
                    "content", file.getContent() != null ? file.getContent() : ""));
        }

        String body = objectMapper.writeValueAsString(Map.of(
                "base_tree", baseTreeSha,
                "tree", treeEntries));

        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "createTree");

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("sha").asText();
    }

    private String createCommit(String message, String treeSha, String parentSha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/commits", API_BASE, encode(owner), encode(repo));

        String body = objectMapper.writeValueAsString(Map.of(
                "message", message,
                "tree", treeSha,
                "parents", List.of(parentSha)));

        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "createCommit");

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("sha").asText();
    }

    private void updateRef(String branchName, String commitSha) throws Exception {
        String url = String.format("%s/repos/%s/%s/git/refs/heads/%s",
                API_BASE, encode(owner), encode(repo), encode(branchName));

        String body = objectMapper.writeValueAsString(Map.of(
                "sha", commitSha,
                "force", false));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "GitHub", "updateRef " + branchName);
    }

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ==================== URL PARSING ====================

    public record ParsedRepoUrl(String owner, String repo) {
    }

    /**
     * Parses various GitHub URL formats and extracts owner and repo.
     * Supports:
     * - HTTPS: https://github.com/owner/repo
     * - HTTPS with .git: https://github.com/owner/repo.git
     * - SSH: git@github.com:owner/repo.git
     * - API URLs: https://api.github.com/repos/owner/repo
     */
    public static ParsedRepoUrl parseRepoUrl(String url) {
        java.util.Objects.requireNonNull(url, "url must not be null");
        String trimmedUrl = url.trim();

        if (trimmedUrl.isEmpty()) {
            throw new IllegalArgumentException("URL must not be empty");
        }

        if (trimmedUrl.endsWith(".git")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 4);
        }
        if (trimmedUrl.endsWith("/")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 1);
        }

        // SSH format: git@github.com:owner/repo
        if (trimmedUrl.startsWith("git@github.com:")) {
            String path = trimmedUrl.substring("git@github.com:".length());
            return parseOwnerAndRepo(path, url);
        }

        // HTTPS format: https://github.com/owner/repo
        if (trimmedUrl.startsWith("https://github.com/")) {
            String path = trimmedUrl.substring("https://github.com/".length());
            return parseOwnerAndRepo(path, url);
        }

        // HTTP format: http://github.com/owner/repo
        if (trimmedUrl.startsWith("http://github.com/")) {
            String path = trimmedUrl.substring("http://github.com/".length());
            return parseOwnerAndRepo(path, url);
        }

        // API URL format: https://api.github.com/repos/owner/repo
        if (trimmedUrl.contains("api.github.com") && trimmedUrl.contains("/repos/")) {
            int repoIndex = trimmedUrl.indexOf("/repos/");
            String path = trimmedUrl.substring(repoIndex + "/repos/".length());
            return parseOwnerAndRepo(path, url);
        }

        throw new IllegalArgumentException("Unrecognized GitHub URL format: " + url);
    }

    private static ParsedRepoUrl parseOwnerAndRepo(String path, String originalUrl) {
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Could not extract owner and repo from URL: " + originalUrl);
        }

        String owner = parts[0];
        String repo = parts[1];

        if (owner.isEmpty() || repo.isEmpty()) {
            throw new IllegalArgumentException("Owner and repo must not be empty: " + originalUrl);
        }

        return new ParsedRepoUrl(owner, repo);
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    public static void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir))
            return;

        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs)
                        throws java.io.IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, java.io.IOException exc)
                        throws java.io.IOException {
                    Files.delete(d);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            log.warn("Failed to clean up directory {}: {}", dir, e.getMessage());
        }
    }
}
