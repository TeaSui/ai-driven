package com.aidriven.lambda;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.CodeContextS3Service;
import com.aidriven.core.service.SecretsService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import com.aidriven.core.util.LambdaInputValidator;
import com.aidriven.core.util.SourceFileFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lambda handler that downloads the FULL target repository from Bitbucket,
 * extracts all source files, and stores the complete code context in S3.
 *
 * This replaces the old keyword-based selective fetching approach.
 * The full repository archive is downloaded to Lambda /tmp (ephemeral storage),
 * extracted, and all source files are read into a structured context document.
 *
 * Output includes an S3 key reference (`codeContextS3Key`) instead of inline content
 * to avoid the Step Functions 256KB payload limit.
 */
@Slf4j
public class BitbucketFetchHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final int MAX_FILE_SIZE_CHARS = 100_000;       // 100KB per file
    private static final long MAX_TOTAL_CONTEXT_CHARS = 3_000_000; // ~3MB total context
    private static final long MAX_FILE_SIZE_BYTES = 500_000;       // Skip files > 500KB on disk

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final SecretsService secretsService;
    private final String bitbucketSecretArn;
    private final CodeContextS3Service codeContextS3Service;

    public BitbucketFetchHandler() {
        this.objectMapper = new ObjectMapper();

        DynamoDbClient dynamoDbClient = DynamoDbClient.create();
        String tableName = System.getenv("DYNAMODB_TABLE_NAME");

        this.ticketStateRepository = new TicketStateRepository(dynamoDbClient, tableName);

        SecretsManagerClient secretsManagerClient = SecretsManagerClient.create();
        this.secretsService = new SecretsService(secretsManagerClient);

        this.bitbucketSecretArn = System.getenv("BITBUCKET_SECRET_ARN");

        String bucketName = System.getenv("CODE_CONTEXT_BUCKET");
        this.codeContextS3Service = new CodeContextS3Service(bucketName);
    }

    // Constructor for testing
    BitbucketFetchHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            SecretsService secretsService, String bitbucketSecretArn,
            CodeContextS3Service codeContextS3Service) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.secretsService = secretsService;
        this.bitbucketSecretArn = bitbucketSecretArn;
        this.codeContextS3Service = codeContextS3Service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        LambdaInputValidator.requireNonEmptyInput(input, "BitbucketFetchHandler");

        String ticketId = LambdaInputValidator.requireString(input, "ticketId");
        String ticketKey = LambdaInputValidator.requireString(input, "ticketKey");

        log.info("BitbucketFetchHandler processing ticket: {} (full repo download)", ticketKey);

        Path repoDir = null;
        try {
            TicketInfo ticket = TicketInfo.builder()
                    .ticketId(ticketId)
                    .ticketKey(ticketKey)
                    .summary((String) input.get("summary"))
                    .description((String) input.get("description"))
                    .labels((List<String>) input.get("labels"))
                    .priority((String) input.get("priority"))
                    .build();

            ticketStateRepository.save(TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.ANALYZING));

            BitbucketClient bitbucketClient = BitbucketClient.fromSecrets(secretsService, bitbucketSecretArn);
            String defaultBranch = bitbucketClient.getDefaultBranch();
            log.info("Using default branch: {}", defaultBranch);

            // 1. Download full repository archive to /tmp
            repoDir = bitbucketClient.downloadArchive(defaultBranch, Path.of("/tmp"));
            log.info("Repository extracted to: {}", repoDir);

            // 2. Build file tree from extracted directory
            List<String> fileTree = buildFileTree(repoDir);
            log.info("File tree contains {} entries", fileTree.size());

            // 3. Collect ALL source files from the extracted repo
            List<FileEntry> sourceFiles = collectSourceFiles(repoDir);
            log.info("Collected {} source files for ticket {}", sourceFiles.size(), ticketKey);

            // 4. Build structured context document
            String contextDocument = buildContextDocument(ticket, fileTree, sourceFiles);
            log.info("Context document: {} chars", contextDocument.length());

            // 5. Store in S3
            String s3Key = codeContextS3Service.storeContext(ticketKey, contextDocument);

            // Return lightweight reference
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ticketId", ticketId);
            output.put("ticketKey", ticketKey);
            output.put("summary", input.get("summary"));
            output.put("description", input.get("description"));
            output.put("labels", input.get("labels"));
            output.put("priority", input.get("priority"));
            output.put("dryRun", input.get("dryRun"));
            output.put("codeContextS3Key", s3Key);
            output.put("fileCount", sourceFiles.size());
            output.put("treeSize", fileTree.size());

            return output;

        } catch (Exception e) {
            log.error("BitbucketFetchHandler failed for ticket: {}", ticketKey, e);
            log.warn("Proceeding without code context due to error");

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ticketId", ticketId);
            output.put("ticketKey", ticketKey);
            output.put("summary", input.get("summary"));
            output.put("description", input.get("description"));
            output.put("labels", input.get("labels"));
            output.put("priority", input.get("priority"));
            output.put("dryRun", input.get("dryRun"));
            output.put("codeContextS3Key", "");
            output.put("fetchError", e.getMessage());

            return output;
        } finally {
            // Always clean up /tmp to free ephemeral storage
            if (repoDir != null) {
                // Clean up the parent extract directory too
                Path extractParent = repoDir.getParent();
                BitbucketClient.deleteDirectory(extractParent);
                log.info("Cleaned up extracted repo from /tmp");
            }
        }
    }

    /**
     * Walks the extracted repo directory and collects all source files.
     * Skips binary files, build outputs, and dependency directories.
     */
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
                if (totalChars >= MAX_TOTAL_CONTEXT_CHARS) {
                    log.info("Reached total context limit ({} chars), stopping at {} files",
                            totalChars, files.size());
                    break;
                }

                try {
                    long fileSize = Files.size(file);
                    if (fileSize > MAX_FILE_SIZE_BYTES) {
                        log.debug("Skipping large file: {} ({} bytes)", file, fileSize);
                        continue;
                    }

                    // Try reading as UTF-8; skip if it's binary
                    String content = Files.readString(file, StandardCharsets.UTF_8);

                    if (SourceFileFilter.isBinaryContent(content)) {
                        continue;
                    }

                    String truncated = SourceFileFilter.truncate(content, MAX_FILE_SIZE_CHARS);
                    String relativePath = repoDir.relativize(file).toString();

                    files.add(new FileEntry(relativePath, truncated));
                    totalChars += truncated.length();
                } catch (java.nio.charset.MalformedInputException e) {
                    // Not a text file, skip silently
                } catch (Exception e) {
                    log.debug("Failed to read file {}: {}", file, e.getMessage());
                }
            }
        }

        log.info("Collected {} source files, {} total chars", files.size(), totalChars);
        return files;
    }

    /**
     * Builds a flat file tree listing from the extracted directory.
     */
    private List<String> buildFileTree(Path repoDir) throws IOException {
        List<String> tree = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(repoDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> tree.add(repoDir.relativize(file).toString()));
        }
        return tree;
    }

    /**
     * Builds a structured context document with full project source code.
     */
    private String buildContextDocument(TicketInfo ticket, List<String> fileTree, List<FileEntry> codeFiles) {
        StringBuilder doc = new StringBuilder();

        // Section 1: Project structure overview
        doc.append("=== PROJECT STRUCTURE ===\n\n");
        doc.append("Full file tree of the target repository:\n\n");
        for (String path : fileTree) {
            doc.append("  ").append(path).append("\n");
        }
        doc.append("\n");

        // Section 2: Package/module summary
        doc.append("=== PACKAGE SUMMARY ===\n\n");
        Map<String, List<String>> packageMap = groupByDirectory(fileTree);
        for (Map.Entry<String, List<String>> entry : packageMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                doc.append("  ").append(entry.getKey()).append("/ (")
                        .append(entry.getValue().size()).append(" files)\n");
            }
        }
        doc.append("\n");

        // Section 3: Full source code of all collected files
        doc.append("=== SOURCE FILES (").append(codeFiles.size()).append(" files) ===\n\n");
        for (FileEntry file : codeFiles) {
            String ext = file.path.contains(".") ? file.path.substring(file.path.lastIndexOf(".") + 1) : "";
            doc.append("--- File: ").append(file.path).append(" ---\n");
            doc.append("```").append(ext).append("\n");
            doc.append(file.content).append("\n");
            doc.append("```\n\n");
        }

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

    private record FileEntry(String path, String content) {}
}
