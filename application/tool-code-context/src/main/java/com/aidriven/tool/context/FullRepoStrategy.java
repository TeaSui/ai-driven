package com.aidriven.tool.context;

import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.util.FileSummarizer;
import com.aidriven.core.util.SourceFileFilter;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.tool.context.model.FileEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy to build context using the entire repository content (within
 * limits).
 *
 * This class provides two strategies for building context:
 * 1. Archive-based: Downloads repository as archive (more efficient)
 * 2. API-based: Fetches files via API (fallback method)
 *
 * <p>
 * If a {@link FileSummarizer} is provided, large files are structurally
 * summarized instead of sent in full, reducing context size by 60–80%.
 */
@Slf4j
public class FullRepoStrategy implements ContextStrategy {

    private static final String TICKET_CONTEXT_HEADER = "=== TICKET CONTEXT ===";
    private static final String PROJECT_STRUCTURE_HEADER = "=== PROJECT STRUCTURE ===";
    private static final String SOURCE_FILES_HEADER = "=== SOURCE FILES ===";
    private static final String REPO_FETCH_TEMP_DIR_PREFIX = "repo-fetch-";
    private static final String ARCHIVE_MODE = "FULL REPOSITORY CONTENT (ARCHIVE)";
    private static final String API_MODE = "FULL REPOSITORY CONTENT (API)";
    private static final String CONTEXT_LIMIT_MESSAGE = "\n... (reached total context limit, skipping remaining files)\n";

    private final SourceControlClient sourceControlClient;
    private final int maxFileSizeChars;
    private final long maxTotalContextChars;
    /** Optional: when non-null, large files are summarized before inclusion. */
    private final FileSummarizer fileSummarizer;

    /** Constructor without summarization (backward-compatible). */
    public FullRepoStrategy(SourceControlClient sourceControlClient,
            int maxFileSizeChars,
            long maxTotalContextChars) {
        this(sourceControlClient, maxFileSizeChars, maxTotalContextChars, null);
    }

    /** Constructor with summarization enabled. */
    public FullRepoStrategy(SourceControlClient sourceControlClient,
            int maxFileSizeChars,
            long maxTotalContextChars,
            FileSummarizer fileSummarizer) {
        this.sourceControlClient = sourceControlClient;
        this.maxFileSizeChars = maxFileSizeChars;
        this.maxTotalContextChars = maxTotalContextChars;
        this.fileSummarizer = fileSummarizer;
    }

    @Override
    public String buildContext(OperationContext context, TicketInfo ticket, BranchName branch) {
        log.info("Building Full Repo Content Context for ticket: {} / tenant: {}", ticket.getTicketKey(),
                context.tenantId());

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(REPO_FETCH_TEMP_DIR_PREFIX);
            Path repoDir = sourceControlClient.downloadArchive(context, branch, tempDir);
            return buildContextFromArchive(repoDir, ticket);
        } catch (UnsupportedOperationException e) {
            log.info("downloadArchive not supported by client, falling back to API-based fetch");
            return buildContextApiBased(context, ticket, branch);
        } catch (Exception e) {
            log.error("Failed to build full repo context via archive", e);
            return buildContextApiBased(context, ticket, branch);
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    private String buildContextFromArchive(Path repoDir, TicketInfo ticket) throws IOException {
        List<String> fileTree = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        long totalChars = 0;

        try (var stream = Files.walk(repoDir)) {
            for (Path p : stream.sorted().toList()) {
                if (Files.isRegularFile(p)) {
                    String relPath = repoDir.relativize(p).toString();
                    fileTree.add(relPath);

                    if (shouldProcessFile(totalChars, Path.of(relPath))) {
                        processFileForContext(repoDir, p, files);
                        totalChars = calculateTotalChars(files);
                    }
                }
            }
        }

        String context = buildContextOutput(ticket, fileTree, files, totalChars, ARCHIVE_MODE);
        log.info("Built Full Repo context with {} files, {} chars using archive", files.size(), totalChars);
        return context;
    }

    private boolean shouldProcessFile(long currentChars, Path file) {
        return currentChars < maxTotalContextChars && SourceFileFilter.isIncluded(file);
    }

    private void processFileForContext(Path repoDir, Path file, List<FileEntry> files) {
        String relPath = repoDir.relativize(file).toString();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!SourceFileFilter.isBinaryContent(content)) {
                String truncated = SourceFileFilter.truncate(content, maxFileSizeChars);
                String processed = applySummarizerIfPresent(truncated, relPath);
                files.add(new FileEntry(relPath, processed));
            }
        } catch (Exception e) {
            log.warn("Failed to read file: {}", relPath, e);
        }
    }

    private long calculateTotalChars(List<FileEntry> files) {
        return files.stream().mapToLong(f -> f.content().length()).sum();
    }

    private String buildContextApiBased(OperationContext context, TicketInfo ticket, BranchName branch) {
        log.info("Falling back to API-based fetch for ticket: {}", ticket.getTicketKey());
        try {
            List<String> fileTree = sourceControlClient.getFileTree(context, branch, null);
            List<FileEntry> files = new ArrayList<>();
            long totalChars = 0;

            for (String filePath : fileTree) {
                if (totalChars >= maxTotalContextChars) {
                    break;
                }

                FileEntry entry = tryFetchIncludedFile(context, branch, filePath);
                if (entry != null) {
                    files.add(entry);
                    totalChars += entry.content().length();
                }
            }

            String contextOutput = buildContextOutput(ticket, fileTree, files, totalChars, API_MODE);
            log.info("Built Full Repo context with {} files, {} chars (API fallback)", files.size(), totalChars);
            return contextOutput;
        } catch (Exception e) {
            log.error("Failed to build full repo context via API", e);
            throw new ContextBuildException("Failed to build full repo context via API", e);
        }
    }

    private FileEntry tryFetchIncludedFile(OperationContext context, BranchName branch, String filePath) {
        if (!SourceFileFilter.isIncluded(Path.of(filePath))) {
            return null;
        }
        return fetchFileContent(context, branch, filePath);
    }

    private FileEntry fetchFileContent(OperationContext context, BranchName branch, String filePath) {
        try {
            String content = sourceControlClient.getFileContent(context, branch, filePath);
            if (content == null || SourceFileFilter.isBinaryContent(content)) {
                return null;
            }

            String truncated = SourceFileFilter.truncate(content, maxFileSizeChars);
            String processed = applySummarizerIfPresent(truncated, filePath);
            return new FileEntry(filePath, processed);
        } catch (Exception e) {
            log.warn("Failed to fetch content for file: {}", filePath, e);
            return null;
        }
    }

    /**
     * Applies file summarization if a summarizer is configured.
     * Extracts the file extension from the path and delegates to
     * {@link FileSummarizer}.
     */
    private String applySummarizerIfPresent(String content, String filePath) {
        if (fileSummarizer == null) {
            return content;
        }
        String ext = extractFileExtension(filePath);
        String summarized = fileSummarizer.summarize(content, ext);
        if (summarized.length() < content.length()) {
            log.debug("Summarized {}: {}->{} chars", filePath, content.length(), summarized.length());
        }
        return summarized;
    }

    private String buildContextOutput(TicketInfo ticket, List<String> fileTree, List<FileEntry> files,
            long totalChars, String mode) {
        StringBuilder sb = new StringBuilder();

        appendTicketHeader(sb, ticket, mode);
        appendProjectStructure(sb, fileTree);
        appendSourceFiles(sb, files);

        if (totalChars >= maxTotalContextChars) {
            sb.append(CONTEXT_LIMIT_MESSAGE);
        }

        return sb.toString();
    }

    private void appendTicketHeader(StringBuilder sb, TicketInfo ticket, String mode) {
        sb.append(TICKET_CONTEXT_HEADER).append("\n");
        sb.append("Key: ").append(ticket.getTicketKey()).append('\n');
        sb.append("Summary: ").append(ticket.getSummary()).append('\n');
        sb.append("Mode: ").append(mode).append("\n\n");
    }

    private void appendProjectStructure(StringBuilder sb, List<String> fileTree) {
        sb.append(PROJECT_STRUCTURE_HEADER).append("\n\nFull file tree:\n\n");
        fileTree.forEach(p -> sb.append("  ").append(p).append("\n"));
        sb.append("\n");
    }

    private void appendSourceFiles(StringBuilder sb, List<FileEntry> files) {
        sb.append(SOURCE_FILES_HEADER).append(" (").append(files.size()).append(" files) ===\n\n");
        for (FileEntry file : files) {
            appendFileContent(sb, file);
        }
    }

    private void appendFileContent(StringBuilder sb, FileEntry file) {
        String ext = extractFileExtension(file.path());
        sb.append("--- File: ").append(file.path()).append(" ---\n");
        sb.append("```").append(ext).append("\n").append(file.content()).append("\n```\n\n");
    }

    private String extractFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        return lastDot > 0 ? filePath.substring(lastDot + 1) : "";
    }

    private void deleteDirectory(Path dir) {
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
            log.warn("Failed to delete temp dir: {}", dir, e);
        }
    }
}
