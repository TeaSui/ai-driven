package com.aidriven.bitbucket;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.util.HttpResponseHandler;
import com.aidriven.core.util.JsonPathExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Client for interacting with Bitbucket Cloud REST API.
 */
@Slf4j
public class BitbucketClient {

    private static final String API_BASE = "https://api.bitbucket.org/2.0";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String workspace;
    private final String repoSlug;
    private final String authHeader;

    public BitbucketClient(String workspace, String repoSlug, String username, String appPassword) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.repoSlug = Objects.requireNonNull(repoSlug, "repoSlug must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(appPassword, "appPassword must not be null");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + appPassword).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a BitbucketClient from secrets.
     */
    public static BitbucketClient fromSecrets(SecretsService secretsService, String secretArn) {
        try {
            JsonNode secrets = secretsService.getSecretJson(secretArn);
            String workspace = secrets.get("workspace").asText();
            String repoSlug = secrets.get("repoSlug").asText();
            String username = secrets.get("username").asText();
            String appPassword = secrets.get("appPassword").asText();
            return new BitbucketClient(workspace, repoSlug, username, appPassword);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create BitbucketClient from secrets", e);
        }
    }

    /**
     * Creates a new branch from the default branch.
     */
    public void createBranch(String branchName, String fromBranch) throws Exception {
        validateBranchName(branchName);
        validateBranchName(fromBranch);

        // First, get the commit hash of the source branch
        String commitHash = getBranchCommitHash(fromBranch);

        String url = String.format("%s/repositories/%s/%s/refs/branches",
                API_BASE, encode(workspace), encode(repoSlug));

        String body = objectMapper.writeValueAsString(Map.of(
                "name", branchName,
                "target", Map.of("hash", commitHash)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 201 Created is expected for new branches
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Bitbucket", "createBranch " + branchName);
        }

        log.info("Created branch: {} from {} ({})", branchName, fromBranch, commitHash);
    }

    /**
     * Gets the latest commit hash of a branch.
     */
    private String getBranchCommitHash(String branchName) throws Exception {
        String url = String.format("%s/repositories/%s/%s/refs/branches/%s",
                API_BASE, encode(workspace), encode(repoSlug), encode(branchName));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "Bitbucket", "getBranchCommitHash " + branchName);

        JsonNode json = objectMapper.readTree(response.body());
        return JsonPathExtractor.getRequiredString(json, "Bitbucket branch", "target", "hash");
    }

    /**
     * Commits files to a branch.
     */
    public String commitFiles(String branchName, List<AgentResult.GeneratedFile> files, String commitMessage)
            throws Exception {
        String url = String.format("%s/repositories/%s/%s/src", API_BASE, workspace, repoSlug);

        // Use UUID-based boundary to avoid collision with file content
        String boundary = "----FormBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder bodyBuilder = new StringBuilder();

        // Add commit message (null-safe)
        String safeCommitMessage = commitMessage != null ? commitMessage : "Auto-generated commit";
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"message\"\r\n\r\n");
        bodyBuilder.append(safeCommitMessage).append("\r\n");

        // Add branch
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"branch\"\r\n\r\n");
        bodyBuilder.append(branchName).append("\r\n");

        // Add files
        for (AgentResult.GeneratedFile file : files) {
            // Sanitize path: remove leading slashes, prevent directory traversal
            String safePath = file.getPath().replaceAll("^[./]*", "").replace("..", "");
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"").append(safePath)
                    .append("\"; filename=\"").append(safePath).append("\"\r\n");
            bodyBuilder.append("Content-Type: text/plain\r\n\r\n");
            bodyBuilder.append(file.getContent() != null ? file.getContent() : "").append("\r\n");
        }

        bodyBuilder.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(bodyBuilder.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 201 Created is expected for commits
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            log.error("Bitbucket commitFiles failed: HTTP {} - Body: {}", response.statusCode(), response.body());
            HttpResponseHandler.checkResponse(response, "Bitbucket", "commitFiles to " + branchName);
        }

        log.info("Committed {} files to branch {}", files.size(), branchName);
        return "success";
    }

    /**
     * Creates a pull request.
     */
    public PullRequestResult createPullRequest(String title, String description, String sourceBranch,
            String destinationBranch) throws Exception {
        String url = String.format("%s/repositories/%s/%s/pullrequests", API_BASE, workspace, repoSlug);

        var prMap = new java.util.HashMap<String, Object>();
        prMap.put("title", title);
        prMap.put("description", description != null ? description : "");
        prMap.put("source", Map.of("branch", Map.of("name", sourceBranch)));
        prMap.put("destination", Map.of("branch", Map.of("name", destinationBranch)));
        prMap.put("close_source_branch", true);
        String body = objectMapper.writeValueAsString(prMap);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 201 Created is expected for new PRs
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            log.error("Bitbucket createPullRequest failed: HTTP {} - Body: {}", response.statusCode(), response.body());
            HttpResponseHandler.checkResponse(response, "Bitbucket",
                    "createPullRequest " + sourceBranch + " -> " + destinationBranch);
        }

        JsonNode json = objectMapper.readTree(response.body());
        String prId = JsonPathExtractor.getRequiredString(json, "Bitbucket PR", "id");
        String prUrl = JsonPathExtractor.getRequiredString(json, "Bitbucket PR", "links", "html", "href");

        log.info("Created pull request: {} -> {}", prUrl, prId);

        return new PullRequestResult(prId, prUrl, sourceBranch);
    }

    /**
     * Gets the default branch of the repository.
     */
    public String getDefaultBranch() throws Exception {
        String url = String.format("%s/repositories/%s/%s", API_BASE, workspace, repoSlug);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponseHandler.checkResponse(response, "Bitbucket", "getDefaultBranch");

        JsonNode json = objectMapper.readTree(response.body());
        return JsonPathExtractor.getRequiredString(json, "Bitbucket repository", "mainbranch", "name");
    }

    private void validateBranchName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Branch name must not be empty");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ==================== CODE EXPLORER APIs ====================

    /**
     * Gets the file tree (list of all files) from a branch.
     * 
     * @param branch The branch name (e.g., "main")
     * @param path   Optional path prefix to filter (e.g., "src/main/java")
     * @return List of file paths
     */
    public List<String> getFileTree(String branch, String path) throws Exception {
        List<String> allFiles = new ArrayList<>();
        java.util.Queue<String> directories = new java.util.LinkedList<>();
        directories.add(path != null ? path : "");

        int maxFiles = 2000; // Safety limit for structural mapping

        while (!directories.isEmpty() && allFiles.size() < maxFiles) {
            String currentPath = directories.poll();
            String nextUrl = String.format("%s/repositories/%s/%s/src/%s/%s?pagelen=100",
                    API_BASE, encode(workspace), encode(repoSlug), encode(branch), encode(currentPath));

            while (nextUrl != null && allFiles.size() < maxFiles) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(nextUrl))
                        .header("Authorization", authHeader)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.warn("Failed to get file tree at {}: {}", nextUrl, response.body());
                    break;
                }

                JsonNode json = objectMapper.readTree(response.body());
                JsonNode values = json.get("values");

                if (values != null && values.isArray()) {
                    for (JsonNode node : values) {
                        String type = node.get("type").asText();
                        String nodePath = node.get("path").asText();
                        if ("commit_file".equals(type)) {
                            allFiles.add(nodePath);
                        } else if ("commit_directory".equals(type)) {
                            directories.add(nodePath);
                        }
                    }
                }

                // Handle pagination
                nextUrl = json.has("next") ? json.get("next").asText() : null;
            }
        }
        return allFiles;
    }

    /**
     * Gets the content of a file from a branch.
     * 
     * @param branch   The branch name
     * @param filePath The path to the file
     * @return The file content as a string
     */
    public String getFileContent(String branch, String filePath) throws Exception {
        String url = String.format("%s/repositories/%s/%s/src/%s/%s",
                API_BASE, encode(workspace), encode(repoSlug), encode(branch), encode(filePath));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "text/plain")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Failed to get file content for {}: {}", filePath, response.body());
            return null;
        }

        return response.body();
    }

    /**
     * Searches for files matching a pattern in the repo.
     * Uses the Bitbucket code search API.
     * 
     * @param query The search query (e.g., "class UserService")
     * @return List of matching file paths
     */
    public List<String> searchFiles(String query) throws Exception {
        String url = String.format("%s/workspaces/%s/search/code?search_query=repo:%s+%s",
                API_BASE, encode(workspace), encode(repoSlug), encode(query));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Code search failed: {}", response.body());
            return List.of();
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode values = json.get("values");

        List<String> files = new ArrayList<>();
        if (values != null && values.isArray()) {
            for (JsonNode node : values) {
                JsonNode file = node.get("file");
                if (file != null) {
                    files.add(file.get("path").asText());
                }
            }
        }
        return files;
    }

    /**
     * Gets core configuration files from the repo (build files, configs).
     */
    public List<RepoFile> getCoreConfigFiles(String branch) throws Exception {
        List<String> configPatterns = List.of(
                "pom.xml", "build.gradle", "settings.gradle", "package.json",
                "README.md", "application.yml", "application.properties");

        List<RepoFile> configFiles = new ArrayList<>();
        List<String> allFiles = getFileTree(branch, null);

        for (String filePath : allFiles) {
            String fileName = filePath.contains("/")
                    ? filePath.substring(filePath.lastIndexOf("/") + 1)
                    : filePath;

            if (configPatterns.stream().anyMatch(pattern -> fileName.equalsIgnoreCase(pattern))) {
                String content = getFileContent(branch, filePath);
                if (content != null) {
                    configFiles.add(new RepoFile(filePath, content));
                }
            }
        }
        return configFiles;
    }

    public record RepoFile(String path, String content) {
    }

    public record PullRequestResult(String id, String url, String branchName) {
    }

    /**
     * Result of parsing a Bitbucket repository URL.
     */
    public record ParsedRepoUrl(String workspace, String repoSlug) {
    }

    /**
     * Parses various Bitbucket URL formats and extracts workspace and repository
     * slug.
     * Supports:
     * - HTTPS: https://bitbucket.org/workspace/repo
     * - HTTPS with .git: https://bitbucket.org/workspace/repo.git
     * - SSH: git@bitbucket.org:workspace/repo.git
     * - SSH without .git: git@bitbucket.org:workspace/repo
     * - API URLs: https://api.bitbucket.org/2.0/repositories/workspace/repo
     *
     * @param url The Bitbucket repository URL to parse
     * @return ParsedRepoUrl containing workspace and repoSlug
     * @throws IllegalArgumentException if the URL format is not recognized
     */
    public static ParsedRepoUrl parseRepoUrl(String url) {
        Objects.requireNonNull(url, "url must not be null");
        String trimmedUrl = url.trim();

        if (trimmedUrl.isEmpty()) {
            throw new IllegalArgumentException("URL must not be empty");
        }

        // Remove trailing .git if present
        if (trimmedUrl.endsWith(".git")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 4);
        }

        // Remove trailing slash if present
        if (trimmedUrl.endsWith("/")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 1);
        }

        // SSH format: git@bitbucket.org:workspace/repo
        if (trimmedUrl.startsWith("git@bitbucket.org:")) {
            String path = trimmedUrl.substring("git@bitbucket.org:".length());
            return parseWorkspaceAndRepo(path, url);
        }

        // HTTPS format: https://bitbucket.org/workspace/repo
        if (trimmedUrl.startsWith("https://bitbucket.org/")) {
            String path = trimmedUrl.substring("https://bitbucket.org/".length());
            return parseWorkspaceAndRepo(path, url);
        }

        // HTTP format: http://bitbucket.org/workspace/repo
        if (trimmedUrl.startsWith("http://bitbucket.org/")) {
            String path = trimmedUrl.substring("http://bitbucket.org/".length());
            return parseWorkspaceAndRepo(path, url);
        }

        // API URL format: https://api.bitbucket.org/2.0/repositories/workspace/repo
        if (trimmedUrl.contains("api.bitbucket.org") && trimmedUrl.contains("/repositories/")) {
            int repoIndex = trimmedUrl.indexOf("/repositories/");
            String path = trimmedUrl.substring(repoIndex + "/repositories/".length());
            return parseWorkspaceAndRepo(path, url);
        }

        throw new IllegalArgumentException("Unrecognized Bitbucket URL format: " + url);
    }

    private static ParsedRepoUrl parseWorkspaceAndRepo(String path, String originalUrl) {
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Could not extract workspace and repo from URL: " + originalUrl);
        }

        String workspace = parts[0];
        String repoSlug = parts[1];

        if (workspace.isEmpty() || repoSlug.isEmpty()) {
            throw new IllegalArgumentException("Workspace and repo slug must not be empty: " + originalUrl);
        }

        return new ParsedRepoUrl(workspace, repoSlug);
    }

    /**
     * Creates a BitbucketClient from a repository URL and credentials.
     *
     * @param repoUrl     The Bitbucket repository URL (HTTPS, SSH, or API format)
     * @param username    The Bitbucket username
     * @param appPassword The Bitbucket app password
     * @return A new BitbucketClient instance
     */
    public static BitbucketClient fromRepoUrl(String repoUrl, String username, String appPassword) {
        ParsedRepoUrl parsed = parseRepoUrl(repoUrl);
        return new BitbucketClient(parsed.workspace(), parsed.repoSlug(), username, appPassword);
    }

    // ==================== FULL REPO DOWNLOAD ====================

    /**
     * Downloads the full repository as a zip archive and extracts it to disk.
     * Uses the Bitbucket Cloud download URL format.
     *
     * @param branch    The branch to download (e.g., "main")
     * @param outputDir The parent directory for extraction (e.g., /tmp)
     * @return Path to the extracted repository root directory
     */
    public Path downloadArchive(String branch, Path outputDir) throws Exception {
        // Bitbucket archive download URL
        String url = String.format("https://bitbucket.org/%s/%s/get/%s.zip",
                encode(workspace), encode(repoSlug), encode(branch));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
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

        // Clean up zip file to free disk space
        Files.deleteIfExists(zipFile);

        // Bitbucket zips contain a single root directory: {user}-{repo}-{hash}/
        // Detect and return the actual repo root
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

    /**
     * Recursively deletes a directory and all its contents.
     * Used for cleaning up extracted archives from /tmp.
     */
    public static void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;

        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up directory {}: {}", dir, e.getMessage());
        }
    }
}
