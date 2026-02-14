package com.aidriven.lambda;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.source.SourceControlClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.aidriven.core.config.FetchConfig;
import com.aidriven.core.util.SourceFileFilter;

import com.aidriven.lambda.context.ContextService;
import com.aidriven.lambda.factory.ServiceFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lambda handler that downloads the target repository from Bitbucket/GitHub,
 * extracts all source files, and stores the complete code context in S3.
 */
@Slf4j
@RequiredArgsConstructor
public class CodeFetchHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final FetchConfig config;
    private final TicketStateRepository ticketStateRepository;
    private final ContextStorageService contextStorageService;
    private final SourceControlClient testSourceControlClient;
    private final ServiceFactory serviceFactory;

    /** No-arg constructor required by AWS Lambda runtime. */
    public CodeFetchHandler() {
        this(ServiceFactory.getInstance());
    }

    private CodeFetchHandler(ServiceFactory factory) {
        this.serviceFactory = factory;
        this.config = factory.getAppConfig().getFetchConfig();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.contextStorageService = factory.getContextStorageService();
        this.testSourceControlClient = null;
    }

    /** Constructor for testing. */
    public CodeFetchHandler(FetchConfig config, TicketStateRepository ticketStateRepository,
            ContextStorageService contextStorageService, ServiceFactory factory,
            SourceControlClient testSourceControlClient) {
        this.config = config;
        this.ticketStateRepository = ticketStateRepository;
        this.contextStorageService = contextStorageService;
        this.serviceFactory = factory;
        this.testSourceControlClient = testSourceControlClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");
        String platform = (String) input.get("platform");
        String repoOwner = (String) input.get("repoOwner");
        String repoSlug = (String) input.get("repoSlug");

        LoggingUtils.appendKey("ticketKey", ticketKey);
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());

        log.info("CodeFetchHandler processing ticket: {}, platform: {}, repo: {}/{}",
                ticketKey, platform, repoOwner, repoSlug);

        Path repoDir = null;
        try {
            TicketInfo ticket = parseTicketInfo(ticketId, ticketKey, input);
            ticketStateRepository.save(TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.ANALYZING));

            log.info("CodeFetchHandler for ticket {}: platform={}, repo={}/{}",
                    ticketKey, platform, repoOwner, repoSlug);
            SourceControlClient client = resolveSourceControlClient(platform, repoOwner, repoSlug);
            ContextService dynamicContextService = serviceFactory.createContextService(client);

            String defaultBranch = client.getDefaultBranch();
            log.info("Using default branch: {}, context mode: {}", defaultBranch, config.contextMode());

            // Strategy 1: Attempt strategy-based fetch via ContextService
            String contextDoc = dynamicContextService.buildContext(ticket, defaultBranch);
            if (contextDoc != null) {
                return buildSuccessOutput(input, ticketKey, contextDoc, config.contextMode(), -1, -1);
            }

            // Strategy 2: Fallback to full repo download
            log.info("ContextService returned null, attempting legacy full repo download");
            repoDir = client.downloadArchive(defaultBranch, Path.of("/tmp"));

            List<String> fileTree = buildFileTree(repoDir);
            List<FileEntry> sourceFiles = collectSourceFiles(repoDir);
            String fullRepoContext = buildFullRepoContextDoc(ticket, fileTree, sourceFiles);

            return buildSuccessOutput(input, ticketKey, fullRepoContext, "FULL_REPO", sourceFiles.size(),
                    fileTree.size());

        } catch (Exception e) {
            log.error("CodeFetchHandler failed for ticket: {}", ticketKey, e);
            return buildErrorOutput(input, ticketId, ticketKey, e);
        } finally {
            cleanup(repoDir);
            // Context cleared by Powertools
        }
    }

    @SuppressWarnings("unchecked")
    private TicketInfo parseTicketInfo(String id, String key, Map<String, Object> input) {
        return TicketInfo.builder()
                .ticketId(id)
                .ticketKey(key)
                .summary((String) input.get("summary"))
                .description((String) input.get("description"))
                .labels((List<String>) input.get("labels"))
                .priority((String) input.get("priority"))
                .build();
    }

    private SourceControlClient resolveSourceControlClient(String platform, String owner, String repo) {
        if (this.testSourceControlClient != null) {
            return this.testSourceControlClient;
        }
        ServiceFactory factory = ServiceFactory.getInstance();
        if ("GITHUB".equalsIgnoreCase(platform)) {
            return factory.getGitHubClient(owner, repo);
        } else {
            return factory.getBitbucketClient(owner, repo);
        }
    }

    private Map<String, Object> buildSuccessOutput(Map<String, Object> input, String key, String doc,
            String mode, int fileCount, int treeSize) {

        log.info("Context built successfully: {} chars, mode: {}", doc.length(), mode);
        String s3Key = contextStorageService.storeContext(key, doc);

        Map<String, Object> out = buildBaseOutput(input);
        out.put("codeContextS3Key", s3Key);
        out.put("contextMode", mode);
        if (fileCount >= 0)
            out.put("fileCount", fileCount);
        if (treeSize >= 0)
            out.put("treeSize", treeSize);
        return out;
    }

    private Map<String, Object> buildErrorOutput(Map<String, Object> input, String id, String key, Exception e) {
        log.warn("Proceeding without code context for ticket {} due to error: {}", key, e.getMessage());
        Map<String, Object> out = buildBaseOutput(input);
        out.put("codeContextS3Key", "");
        out.put("fetchError", e.getMessage());
        return out;
    }

    private Map<String, Object> buildBaseOutput(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", input.get("ticketId"));
        out.put("ticketKey", input.get("ticketKey"));
        out.put("summary", input.get("summary"));
        out.put("description", input.get("description"));
        out.put("labels", input.get("labels"));
        out.put("priority", input.get("priority"));
        out.put("dryRun", input.get("dryRun"));
        out.put("resolvedModel", input.get("resolvedModel"));

        // Pass through repo metadata
        Stream.of("platform", "repoOwner", "repoSlug")
                .forEach(k -> {
                    if (input.containsKey(k))
                        out.put(k, input.get(k));
                });

        return out;
    }

    private List<FileEntry> collectSourceFiles(Path repoDir) throws IOException {
        List<FileEntry> files = new ArrayList<>();
        long totalChars = 0;

        try (Stream<Path> stream = Files.walk(repoDir)) {
            List<Path> allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(SourceFileFilter::isIncluded)
                    .sorted()
                    .toList();

            for (Path file : allFiles) {
                if (totalChars >= config.maxTotalContextChars())
                    break;

                try {
                    long size = Files.size(file);
                    if (size > config.maxFileSizeBytes())
                        continue;

                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (SourceFileFilter.isBinaryContent(content))
                        continue;

                    String truncated = SourceFileFilter.truncate(content, config.maxFileSizeChars());
                    files.add(new FileEntry(repoDir.relativize(file).toString(), truncated));
                    totalChars += truncated.length();
                } catch (IOException ignored) {
                }
            }
        }
        return files;
    }

    private List<String> buildFileTree(Path repoDir) throws IOException {
        try (Stream<Path> stream = Files.walk(repoDir)) {
            return stream.filter(Files::isRegularFile)
                    .sorted()
                    .map(file -> repoDir.relativize(file).toString())
                    .toList();
        }
    }

    private String buildFullRepoContextDoc(TicketInfo ticket, List<String> fileTree, List<FileEntry> files) {
        StringBuilder doc = new StringBuilder();
        doc.append("=== PROJECT STRUCTURE ===\n\nFull file tree:\n\n");
        fileTree.forEach(p -> doc.append("  ").append(p).append("\n"));
        doc.append("\n");

        doc.append("=== PACKAGE SUMMARY ===\n\n");
        groupByDirectory(fileTree).forEach((dir, family) -> {
            if (family.size() > 1)
                doc.append("  ").append(dir).append("/ (").append(family.size()).append(" files)\n");
        });
        doc.append("\n");

        doc.append("=== SOURCE FILES (").append(files.size()).append(" files) ===\n\n");
        files.forEach(f -> {
            String ext = f.path.contains(".") ? f.path.substring(f.path.lastIndexOf(".") + 1) : "";
            doc.append("--- File: ").append(f.path).append(" ---\n");
            doc.append("```").append(ext).append("\n").append(f.content).append("\n```\n\n");
        });

        return doc.toString();
    }

    private Map<String, List<String>> groupByDirectory(List<String> fileTree) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String path : fileTree) {
            int lastSlash = path.lastIndexOf('/');
            String dir = lastSlash > 0 ? path.substring(0, lastSlash) : ".";
            map.computeIfAbsent(dir, k -> new ArrayList<>()).add(path);
        }
        return map;
    }

    private void cleanup(Path repoDir) {
        if (repoDir != null) {
            deleteDirectory(repoDir.getParent());
            log.info("Cleaned up extracted repo from /tmp");
        }
    }

    private record FileEntry(String path, String content) {
    }

    static void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir))
            return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete directory {}: {}", dir, e.getMessage());
        }
    }
}
