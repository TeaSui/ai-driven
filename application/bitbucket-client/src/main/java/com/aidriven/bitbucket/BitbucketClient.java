package com.aidriven.bitbucket;

import com.aidriven.core.exception.ConfigurationException;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.RepositoryReader;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.util.HttpResponseHandler;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.spi.provider.SourceControlProvider.RepoFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for interacting with Bitbucket Cloud REST API.
 * Implements both SourceControlClient (internal) and SourceControlProvider
 * (SPI).
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BitbucketClient implements SourceControlClient, SourceControlProvider {

    private final String API_BASE = "https://api.bitbucket.org/2.0";
    private final @NonNull String workspace;
    private final @NonNull String repoSlug;
    private final @NonNull String authHeader;
    private final @NonNull HttpClient httpClient;
    private final @NonNull ObjectMapper objectMapper;

    /** Configuration POJO for Bitbucket */
    public record BitbucketSecret(String workspace, String repoSlug, String username, String appPassword) {
    }

    public BitbucketClient(String workspace, String repoSlug, String username, String appPassword) {
        this.workspace = workspace;
        this.repoSlug = repoSlug;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = new ObjectMapper();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + appPassword).getBytes(StandardCharsets.UTF_8));
    }

    public static BitbucketClient fromSecrets(SecretsService secretsManager, String secretArn) {
        try {
            BitbucketSecret secret = secretsManager.getSecretAs(secretArn, BitbucketSecret.class);
            if (secret == null || secret.workspace() == null || secret.repoSlug() == null || secret.username() == null
                    || secret.appPassword() == null) {
                throw new ConfigurationException("BitbucketClient: secret '" + secretArn
                        + "' is missing required fields (workspace, repoSlug, username, appPassword)");
            }
            return new BitbucketClient(secret.workspace(), secret.repoSlug(), secret.username(), secret.appPassword());
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigurationException("BitbucketClient init failed for secret: " + secretArn, e);
        }
    }

    public BitbucketClient withRepository(String workspace, String repoSlug) {
        return new BitbucketClient(workspace, repoSlug, authHeader, httpClient, objectMapper);
    }

    @Override
    public String getName() {
        return "bitbucket";
    }

    @Override
    public boolean supports(String repositoryUri) {
        return repositoryUri != null && repositoryUri.toLowerCase().contains("bitbucket.org")
                && repositoryUri.contains("/" + workspace + "/" + repoSlug);
    }

    // --- RepositoryReader / SourceControlClient / SourceControlProvider ---

    @Override
    public BranchName getDefaultBranch(OperationContext context) throws Exception {
        String url = String.format("%s/repositories/%s/%s", API_BASE, encode(workspace), encode(repoSlug));
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "getDefaultBranch");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "getDefaultBranch");
        return BranchName.of(objectMapper.readTree(response.body()).get("mainbranch").get("name").asText());
    }

    @Override
    public void createBranch(OperationContext context, @NonNull BranchName branchName, @NonNull BranchName fromBranch)
            throws Exception {
        String fromHash = getBranchCommitHash(fromBranch.name());
        String url = String.format("%s/repositories/%s/%s/refs/branches", API_BASE, encode(workspace),
                encode(repoSlug));
        String body = objectMapper.writeValueAsString(
                Map.of("name", branchName.name(), "target", Map.of("hash", fromHash)));
        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "createBranch");
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Bitbucket", "createBranch " + branchName.name());
        }
    }

    private String getBranchCommitHash(String branch) throws Exception {
        String url = String.format("%s/repositories/%s/%s/refs/branches/%s", API_BASE, encode(workspace),
                encode(repoSlug),
                encode(branch));
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString()), "Bitbucket",
                "getBranchCommitHash");
        if (response.statusCode() != 200) {
            HttpResponseHandler.checkResponse(response, "Bitbucket", "getBranchCommitHash");
        }
        return objectMapper.readTree(response.body()).get("target").get("hash").asText();
    }

    @Override
    public String commitFiles(OperationContext context, @NonNull BranchName branchName,
            @NonNull List<AgentResult.GeneratedFile> files, String commitMessage) throws Exception {
        List<RepoFile> repoFiles = files.stream()
                .map(f -> new RepoFile(f.getPath(), f.getContent(), f.getOperation().name()))
                .toList();
        return pushFiles(context, branchName, repoFiles, commitMessage);
    }

    @Override
    public String pushFiles(OperationContext context, BranchName branchName, List<RepoFile> files, String commitMessage)
            throws Exception {
        Objects.requireNonNull(files, "files must not be null");
        String url = String.format("%s/repositories/%s/%s/src", API_BASE, encode(workspace), encode(repoSlug));
        String boundary = "----FormBoundary" + java.util.UUID.randomUUID().toString();
        StringBuilder bodyBuilder = new StringBuilder();

        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"message\"\r\n\r\n");
        bodyBuilder.append(commitMessage != null ? commitMessage : "Auto-commit").append("\r\n");

        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"branch\"\r\n\r\n");
        bodyBuilder.append(branchName.name()).append("\r\n");

        for (RepoFile file : files) {
            String safePath = file.path().replaceAll("^[./]*", "").replace("..", "");
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"").append(safePath).append("\"; filename=\"")
                    .append(safePath).append("\"\r\n");
            bodyBuilder.append("Content-Type: text/plain\r\n\r\n");
            bodyBuilder.append(file.content() != null ? file.content() : "").append("\r\n");
        }
        bodyBuilder.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(bodyBuilder.toString()))
                .build();

        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "pushFiles");
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new RuntimeException("Commit failed: " + response.body());
        }
        return "success";
    }

    @Override
    public SourceControlProvider.PullRequestResult openPullRequest(OperationContext context, String title,
            String description, BranchName sourceBranch, BranchName destinationBranch) throws Exception {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(sourceBranch, "sourceBranch must not be null");
        Objects.requireNonNull(destinationBranch, "destinationBranch must not be null");

        String url = String.format("%s/repositories/%s/%s/pullrequests", API_BASE, encode(workspace), encode(repoSlug));
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("title", title);
        bodyMap.put("description", description);
        bodyMap.put("source", Map.of("branch", Map.of("name", sourceBranch.name())));
        bodyMap.put("destination", Map.of("branch", Map.of("name", destinationBranch.name())));
        String body = objectMapper.writeValueAsString(bodyMap);
        HttpRequest request = buildPostRequest(url, body);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "openPullRequest");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "openPullRequest");
        JsonNode json = objectMapper.readTree(response.body());
        return new SourceControlProvider.PullRequestResult(json.get("id").asText(),
                json.get("links").get("html").get("href").asText(), sourceBranch, title);
    }

    @Override
    public SourceControlProvider.PullRequestResult createPullRequest(OperationContext context, String title,
            String description, BranchName sourceBranch, BranchName destinationBranch)
            throws Exception {
        return openPullRequest(context, title, description, sourceBranch, destinationBranch);
    }

    @Override
    public List<RepositoryReader.PullRequestSummary> listPullRequests(OperationContext context) throws Exception {
        String url = String.format("%s/repositories/%s/%s/pullrequests?state=OPEN", API_BASE, encode(workspace),
                encode(repoSlug));
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket",
                "listPullRequests");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "listPullRequests");

        JsonNode root = objectMapper.readTree(response.body());
        List<RepositoryReader.PullRequestSummary> results = new ArrayList<>();
        if (root.has("values")) {
            for (JsonNode node : root.get("values")) {
                results.add(new RepositoryReader.PullRequestSummary(
                        node.get("id").asText(),
                        node.get("links").get("html").get("href").asText(),
                        BranchName.of(node.get("source").get("branch").get("name").asText()),
                        node.get("title").asText()));
            }
        }
        return results;
    }

    @Override
    public List<String> getFileTree(OperationContext context, @NonNull BranchName branch, String path)
            throws Exception {
        String url = String.format("%s/repositories/%s/%s/src/%s/%s?pagelen=100", API_BASE, encode(workspace),
                encode(repoSlug), branch.name(),
                path != null ? encode(path) : "");
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "getFileTree");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "getFileTree");
        JsonNode json = objectMapper.readTree(response.body());
        List<String> files = new ArrayList<>();
        if (json.has("values")) {
            for (JsonNode node : json.get("values")) {
                if (node.has("path") && node.path("type").asText().equals("commit_file")) {
                    files.add(node.get("path").asText());
                }
            }
        }
        return files;
    }

    @Override
    public String getFileContent(OperationContext context, @NonNull BranchName branch, String filePath)
            throws Exception {
        String url = String.format("%s/repositories/%s/%s/src/%s/%s", API_BASE, encode(workspace), encode(repoSlug),
                branch.name(),
                encode(filePath));
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "getFileContent");
        if (response.statusCode() == 404)
            return null;
        HttpResponseHandler.checkResponse(response, "Bitbucket", "getFileContent");
        return response.body();
    }

    @Override
    public List<String> searchFiles(OperationContext context, String query) throws Exception {
        String url = String.format("%s/repositories/%s/%s/src?q=%s", API_BASE, encode(workspace), encode(repoSlug),
                encode(query));
        HttpRequest request = buildGetRequest(url);
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "searchFiles");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "searchFiles");
        JsonNode json = objectMapper.readTree(response.body());
        List<String> paths = new ArrayList<>();
        if (json.has("values")) {
            for (JsonNode node : json.get("values")) {
                paths.add(node.get("path").asText());
            }
        }
        return paths;
    }

    @Override
    public String addPrComment(OperationContext context, String prNumber, String body) throws Exception {
        String url = String.format("%s/repositories/%s/%s/pullrequests/%s/comments", API_BASE, encode(workspace),
                encode(repoSlug),
                prNumber);
        Map<String, Object> bodyMap = Map.of("content", Map.of("raw", body));
        HttpRequest request = buildPostRequest(url, objectMapper.writeValueAsString(bodyMap));
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "addPrComment");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "addPrComment");
        return objectMapper.readTree(response.body()).get("id").asText();
    }

    @Override
    public void addPrComment(OperationContext context, String repoOwner, String repoSlug, String prNumber,
            String body) {
        try {
            addPrComment(context, prNumber, body);
        } catch (Exception e) {
            log.error("Failed to add PR comment: {}", e.getMessage(), e);
        }
    }

    @Override
    public String addPrCommentReply(OperationContext context, String prNumber, String parentCommentId, String body)
            throws Exception {
        String url = String.format("%s/repositories/%s/%s/pullrequests/%s/comments", API_BASE, encode(workspace),
                encode(repoSlug),
                prNumber);
        Map<String, Object> bodyMap = Map.of(
                "content", Map.of("raw", body),
                "parent", Map.of("id", Long.parseLong(parentCommentId)));
        HttpRequest request = buildPostRequest(url, objectMapper.writeValueAsString(bodyMap));
        HttpResponse<String> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), "Bitbucket", "addPrCommentReply");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "addPrCommentReply");
        return objectMapper.readTree(response.body()).get("id").asText();
    }

    @Override
    public void addPrCommentReply(OperationContext context, String repoOwner, String repoSlug, String prNumber,
            String parentCommentId, String body) {
        try {
            addPrCommentReply(context, prNumber, parentCommentId, body);
        } catch (Exception e) {
            log.error("Failed to add PR comment reply: {}", e.getMessage(), e);
        }
    }

    @Override
    public Path downloadArchive(OperationContext context, @NonNull BranchName branch, Path outputDir) throws Exception {
        String url = String.format("%s/repositories/%s/%s/get/%s.zip", API_BASE, encode(workspace), encode(repoSlug),
                branch.name());
        HttpRequest request = buildGetRequest(url);
        HttpResponse<byte[]> response = HttpResponseHandler.sendWithRetry(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()), "Bitbucket",
                "downloadArchive");
        HttpResponseHandler.checkResponse(response, "Bitbucket", "downloadArchive");

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
        log.warn("getWorkflowRunLogs is not yet implemented for Bitbucket Pipeline APIs");
        return "Bitbucket log fetching is pending implementation.";
    }

    private void unzip(Path zipFile, Path extractDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
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

    private Path findRepoRoot(Path extractDir) throws IOException {
        try (var stream = Files.list(extractDir)) {
            return stream.filter(Files::isDirectory)
                    .findFirst()
                    .orElse(extractDir);
        }
    }

    // --- Helpers ---
    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader).GET().build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    public static ParsedRepoUrl parseRepoUrl(String url) {
        Objects.requireNonNull(url, "url must not be null");
        if (url.isBlank())
            throw new IllegalArgumentException("url must not be blank");

        // Handle SSH: git@bitbucket.org:myworkspace/myrepo.git
        if (url.startsWith("git@")) {
            if (!url.contains("bitbucket.org")) {
                throw new IllegalArgumentException("Invalid repository URL: " + url);
            }
            int colonIdx = url.indexOf(':');
            if (colonIdx != -1) {
                String path = url.substring(colonIdx + 1);
                String[] parts = path.split("/");
                if (parts.length >= 2) {
                    return new ParsedRepoUrl(parts[0], parts[1].replace(".git", ""));
                }
            }
            throw new IllegalArgumentException("Invalid repo URL format");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Invalid repository URL: " + url);
        }

        if (!url.contains("bitbucket.org")) {
            throw new IllegalArgumentException("Invalid repository URL: " + url);
        }

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Invalid repo URL format");
            }

            if (path.startsWith("/"))
                path = path.substring(1);

            if (path.endsWith("/"))
                path = path.substring(0, path.length() - 1);

            // Handle API URL: https://api.bitbucket.org/2.0/repositories/workspace/slug
            if (path.startsWith("2.0/repositories/")) {
                path = path.substring("2.0/repositories/".length());
            }

            String[] parts = path.split("/");
            if (parts.length < 2)
                throw new IllegalArgumentException("Invalid repo URL format");

            String workspace = parts[0];
            String slug = parts[1].replace(".git", "");
            return new ParsedRepoUrl(workspace, slug);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException)
                throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Invalid repository URL: " + url, e);
        }
    }

    public record ParsedRepoUrl(String workspace, String repoSlug) {
    }
}
