package com.aidriven.tool.context;

import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.context.DirectoryScanner;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class SmartContextStrategy implements ContextStrategy {

    private static final int MIN_FILES_THRESHOLD = 3;
    private static final int MAX_FILES_TO_FETCH = 50;
    private static final int MIN_KEYWORD_LENGTH = 3;
    private static final int MAX_SEARCH_QUERIES = 3;
    private static final int MAX_FILES_PER_COMPONENT = 20;
    private static final String TRUNCATION_MESSAGE = "\n\n// ... truncated (exceeded %d char limit)";
    private static final String CONTEXT_MODE = "SMART INCREMENTAL";

    private static final List<String> CONFIG_FILE_NAMES = List.of(
            "pom.xml", "build.gradle", "settings.gradle", "package.json",
            "README.md", ".gitignore", "application.yml", "application.properties");

    private final SourceControlClient sourceControlClient;
    private final int maxFileChars;
    private final int maxTotalChars;

    @Override
    public String buildContext(OperationContext context, TicketInfo ticket, BranchName branch) {
        log.info("Building Smart Context for ticket: {} / tenant: {}", ticket.getTicketKey(), context.tenantId());

        List<String> keywords = extractKeywords(ticket.getSummary());
        if (keywords.isEmpty()) {
            log.warn("No keywords found in summary — strategy failed");
            return null;
        }

        List<String> fileTree = fetchFileTree(context, branch);
        if (fileTree.isEmpty()) {
            return null;
        }

        Set<String> matchedPaths = findMatchingPaths(context, keywords, fileTree);
        addCoreConfigFiles(fileTree, matchedPaths);

        Map<String, String> fileContents = fetchFileContents(context, matchedPaths, branch);

        if (fileContents.size() < MIN_FILES_THRESHOLD) {
            log.warn("Only {} files successfully fetched (threshold {}), strategy failed",
                    fileContents.size(), MIN_FILES_THRESHOLD);
            return null;
        }

        return buildDocument(ticket, fileContents, fileTree);
    }

    private List<String> fetchFileTree(OperationContext context, BranchName branch) {
        try {
            return sourceControlClient.getFileTree(context, branch, null);
        } catch (Exception e) {
            log.warn("Failed to fetch file tree: {}", e.getMessage());
            return List.of();
        }
    }

    private Set<String> findMatchingPaths(OperationContext context, List<String> keywords, List<String> fileTree) {
        Set<String> matchedPaths = new LinkedHashSet<>();

        // Map keywords to components and filter files
        Map<String, List<String>> componentMap = DirectoryScanner.scan(fileTree);
        for (String keyword : keywords) {
            matchPathsByKeyword(keyword, fileTree, componentMap, matchedPaths);
        }

        // Search for files using keyword queries
        searchFilesByKeywords(context, keywords, matchedPaths);

        return matchedPaths;
    }

    private void matchPathsByKeyword(String keyword, List<String> fileTree,
            Map<String, List<String>> componentMap, Set<String> matchedPaths) {
        String lower = keyword.toLowerCase();
        if (componentMap.containsKey(lower)) {
            List<String> paths = componentMap.get(lower);
            log.info("Mapping concept '{}' to paths: {}", keyword, paths);
            for (String path : paths) {
                fileTree.stream()
                        .filter(f -> f.startsWith(path + "/"))
                        .limit(MAX_FILES_PER_COMPONENT)
                        .forEach(matchedPaths::add);
            }
        }
    }

    private void searchFilesByKeywords(OperationContext context, List<String> keywords, Set<String> matchedPaths) {
        int queriesUsed = 0;
        for (String query : keywords) {
            if (queriesUsed >= MAX_SEARCH_QUERIES) {
                break;
            }
            try {
                List<String> results = sourceControlClient.searchFiles(context, query);
                matchedPaths.addAll(results);
                queriesUsed++;
            } catch (Exception e) {
                log.warn("Search failed for query '{}': {}", query, e.getMessage());
            }
        }
    }

    private Map<String, String> fetchFileContents(OperationContext context, Set<String> paths, BranchName branch) {
        Map<String, String> contents = new LinkedHashMap<>();
        long totalChars = 0;

        for (String path : paths.stream().limit(MAX_FILES_TO_FETCH).toList()) {
            if (totalChars >= maxTotalChars) {
                break;
            }

            String content = tryFetchFileContent(context, branch, path);
            if (content != null) {
                contents.put(path, content);
                totalChars += content.length();
            }
        }
        return contents;
    }

    private String tryFetchFileContent(OperationContext context, BranchName branch, String path) {
        try {
            String content = sourceControlClient.getFileContent(context, branch, path);
            if (content == null) {
                return null;
            }
            return truncateIfNeeded(content);
        } catch (Exception e) {
            log.warn("Failed to fetch file {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String truncateIfNeeded(String content) {
        if (content.length() > maxFileChars) {
            return content.substring(0, maxFileChars) + String.format(TRUNCATION_MESSAGE, maxFileChars);
        }
        return content;
    }

    private void addCoreConfigFiles(List<String> fileTree, Set<String> matchedPaths) {
        for (String filePath : fileTree) {
            String fileName = extractFileName(filePath);
            if (isConfigFile(fileName)) {
                matchedPaths.add(filePath);
            }
        }
    }

    private String extractFileName(String filePath) {
        if (!filePath.contains("/")) {
            return filePath;
        }
        return filePath.substring(filePath.lastIndexOf("/") + 1);
    }

    private boolean isConfigFile(String fileName) {
        return CONFIG_FILE_NAMES.stream().anyMatch(fileName::equalsIgnoreCase);
    }

    private String buildDocument(TicketInfo ticket, Map<String, String> fileContents, List<String> fileTree) {
        StringBuilder sb = new StringBuilder();

        appendHeader(sb, ticket);
        appendFileTree(sb, fileTree);
        appendFileContents(sb, fileContents);

        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, TicketInfo ticket) {
        sb.append("=== TICKET CONTEXT ===\n");
        sb.append("Key: ").append(ticket.getTicketKey()).append('\n');
        sb.append("Summary: ").append(ticket.getSummary()).append('\n');
        sb.append("Mode: ").append(CONTEXT_MODE).append("\n\n");
    }

    private void appendFileTree(StringBuilder sb, List<String> fileTree) {
        sb.append("=== PROJECT STRUCTURE ===\n\nFull file tree:\n\n");
        fileTree.forEach(p -> sb.append("  ").append(p).append("\n"));
        sb.append("\n");
    }

    private void appendFileContents(StringBuilder sb, Map<String, String> fileContents) {
        sb.append("=== SOURCE FILES (").append(fileContents.size()).append(" files) ===\n\n");
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            sb.append("--- FILE: ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n\n");
        }
    }

    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("\\W+"))
                .filter(s -> s.length() > MIN_KEYWORD_LENGTH)
                .distinct()
                .toList();
    }
}
