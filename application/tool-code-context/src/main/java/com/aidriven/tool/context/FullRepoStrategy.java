package com.aidriven.tool.context;

import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.util.SourceFileFilter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Strategy to build context using the entire repository content (within
 * limits).
 */
@Slf4j
@RequiredArgsConstructor
public class FullRepoStrategy implements ContextStrategy {

    private final SourceControlClient sourceControlClient;
    private final int maxFileSizeChars;
    private final long maxTotalContextChars;

    @Override
    public String buildContext(TicketInfo ticket, String branch) {
        log.info("Building Full Repo Content Context for ticket: {}", ticket.getTicketKey());

        try {
            List<String> fileTree = sourceControlClient.getFileTree(branch, null);

            StringBuilder sb = new StringBuilder();
            sb.append("=== TICKET CONTEXT ===\n");
            sb.append("Key: ").append(ticket.getTicketKey()).append('\n');
            sb.append("Summary: ").append(ticket.getSummary()).append('\n');
            sb.append("Mode: FULL REPOSITORY CONTENT\n\n");

            sb.append("=== PROJECT STRUCTURE ===\n\nFull file tree:\n\n");
            fileTree.forEach(p -> sb.append("  ").append(p).append("\n"));
            sb.append("\n");

            sb.append("=== SOURCE FILES ===\n\n");

            long totalChars = 0;
            int fileCount = 0;

            for (String filePath : fileTree) {
                if (totalChars >= maxTotalContextChars) {
                    log.warn("Reached max total context limit ({} chars), skipping remaining files",
                            maxTotalContextChars);
                    sb.append("\n... (reached total context limit, skipping remaining files)\n");
                    break;
                }

                if (!SourceFileFilter.isIncluded(Path.of(filePath))) {
                    continue;
                }

                try {
                    String content = sourceControlClient.getFileContent(branch, filePath);
                    if (content == null || SourceFileFilter.isBinaryContent(content)) {
                        continue;
                    }

                    String truncated = SourceFileFilter.truncate(content, maxFileSizeChars);
                    String ext = filePath.contains(".") ? filePath.substring(filePath.lastIndexOf(".") + 1) : "";

                    sb.append("--- File: ").append(filePath).append(" ---\n");
                    sb.append("```").append(ext).append("\n").append(truncated).append("\n```\n\n");

                    totalChars += truncated.length();
                    fileCount++;
                } catch (Exception e) {
                    log.warn("Failed to fetch content for file: {}", filePath, e);
                }
            }

            log.info("Built Full Repo context with {} files, {} chars", fileCount, totalChars);
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to build full repo context", e);
            throw new RuntimeException(e);
        }
    }
}
