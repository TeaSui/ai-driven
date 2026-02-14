package com.aidriven.lambda.context;

import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.context.DirectoryScanner;
import com.aidriven.core.model.TicketInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class SmartContextStrategy implements ContextStrategy {

    private final SourceControlClient sourceControlClient;
    private final int maxFileChars;
    private final int maxTotalChars;

    private static final int MIN_FILES_THRESHOLD = 3;
    private static final int MAX_FILES_TO_FETCH = 50;

    @Override
    public String buildContext(TicketInfo ticket, String branch) {
        log.info("Building Smart Context for ticket: {}", ticket.getTicketKey());

        // Simple keyword extraction from summary
        List<String> keywords = extractBasicKeywords(ticket.getSummary());
        if (keywords.isEmpty()) {
            log.warn("No keywords found in summary — strategy failed");
            return null;
        }

        List<String> fileTree;
        try {
            fileTree = sourceControlClient.getFileTree(branch, null);
        } catch (Exception e) {
            log.warn("Failed to fetch file tree: {}", e.getMessage());
            return null;
        }

        Map<String, List<String>> componentMap = DirectoryScanner.scan(fileTree);
        Set<String> matchedPaths = new LinkedHashSet<>();

        for (String keyword : keywords) {
            String lower = keyword.toLowerCase();
            if (componentMap.containsKey(lower)) {
                List<String> paths = componentMap.get(lower);
                log.info("Mapping concept '{}' to paths: {}", keyword, paths);
                for (String path : paths) {
                    fileTree.stream()
                            .filter(f -> f.startsWith(path + "/"))
                            .limit(20)
                            .forEach(matchedPaths::add);
                }
            }
        }

        // Try searching for the first few keywords
        int queriesUsed = 0;
        for (String query : keywords) {
            if (queriesUsed >= 3)
                break;
            try {
                List<String> results = sourceControlClient.searchFiles(query);
                matchedPaths.addAll(results);
                queriesUsed++;
            } catch (Exception e) {
                log.warn("Search failed for query '{}': {}", query, e.getMessage());
            }
        }

        // Add core config files (build files, configs) from the file tree
        addCoreConfigFiles(fileTree, matchedPaths);

        Map<String, String> fileContents = fetchFileContents(matchedPaths, branch);

        if (fileContents.size() < MIN_FILES_THRESHOLD) {
            log.warn("Only {} files successfully fetched (threshold {}), strategy failed",
                    fileContents.size(), MIN_FILES_THRESHOLD);
            return null;
        }

        return buildDocument(ticket, fileContents, fileTree);
    }

    private Map<String, String> fetchFileContents(Set<String> paths, String branch) {
        Map<String, String> contents = new LinkedHashMap<>();
        int totalChars = 0;

        for (String path : paths.stream().limit(MAX_FILES_TO_FETCH).collect(Collectors.toList())) {
            if (totalChars >= maxTotalChars)
                break;
            try {
                String content = sourceControlClient.getFileContent(branch, path);
                if (content != null) {
                    if (content.length() > maxFileChars) {
                        content = content.substring(0, maxFileChars)
                                + "\n\n// ... truncated (exceeded " + maxFileChars + " char limit)";
                    }
                    contents.put(path, content);
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.warn("Failed to fetch file {}: {}", path, e.getMessage());
            }
        }
        return contents;
    }

    private static final List<String> CONFIG_FILE_NAMES = List.of(
            "pom.xml", "build.gradle", "settings.gradle", "package.json",
            "README.md", ".gitignore", "application.yml", "application.properties");

    private void addCoreConfigFiles(List<String> fileTree, Set<String> matchedPaths) {
        for (String filePath : fileTree) {
            String fileName = filePath.contains("/")
                    ? filePath.substring(filePath.lastIndexOf("/") + 1)
                    : filePath;
            if (CONFIG_FILE_NAMES.stream().anyMatch(p -> fileName.equalsIgnoreCase(p))) {
                matchedPaths.add(filePath);
            }
        }
    }

    private String buildDocument(TicketInfo ticket, Map<String, String> fileContents, List<String> fileTree) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TICKET CONTEXT ===\n");
        sb.append("Key: ").append(ticket.getTicketKey()).append('\n');
        sb.append("Summary: ").append(ticket.getSummary()).append('\n');
        sb.append("Mode: SMART INCREMENTAL\n\n");

        sb.append("=== PROJECT STRUCTURE ===\n\nFull file tree:\n\n");
        fileTree.forEach(p -> sb.append("  ").append(p).append("\n"));
        sb.append("\n");

        sb.append("=== SOURCE FILES (").append(fileContents.size()).append(" files) ===\n\n");
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            sb.append("--- FILE: ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    private List<String> extractBasicKeywords(String text) {
        if (text == null || text.isBlank())
            return List.of();
        return java.util.Arrays.stream(text.split("\\W+"))
                .filter(s -> s.length() > 3)
                .distinct()
                .collect(Collectors.toList());
    }
}
