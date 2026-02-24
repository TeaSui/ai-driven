package com.aidriven.core.source;

import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;

import java.util.List;

/**
 * Read-only operations for source control repositories.
 * Segregated from write operations per Interface Segregation Principle (ISP).
 *
 * <p>
 * PSE-Grade Benefits:
 * - Single Responsibility: focused only on queries
 * - Easier Testing: mock only what's needed
 * - Safer Dependencies: read-only guarantee at type level
 * - Clear Contracts: clients know this interface won't modify state
 * </p>
 *
 * Platform Implementations:
 * - GitHub: REST API read operations
 * - Bitbucket: REST API read operations
 *
 * @since 1.0
 */
public interface RepositoryReader {

    /**
     * Gets the default branch name for the repository.
     * Typically "main" or "master".
     *
     * @param context Operation context (for tracing and multi-tenancy)
     * @return The default branch name
     * @throws Exception if the operation fails (network, permission, etc.)
     */
    BranchName getDefaultBranch(OperationContext context) throws Exception;

    /**
     * Gets the list of files in the repository.
     * Includes relative paths for filtering and searching.
     *
     * @param context Operation context
     * @param branch  Branch name to query
     * @param path    Optional path filter (e.g., "src/main" returns only files
     *                under that path)
     * @return List of file paths (relative to repository root)
     * @throws Exception if the operation fails
     */
    List<String> getFileTree(OperationContext context, BranchName branch, String path) throws Exception;

    /**
     * Gets the content of a specific file.
     *
     * @param context  Operation context
     * @param branch   Branch name
     * @param filePath File path relative to repository root
     * @return File content, or null if file not found
     * @throws Exception if the operation fails
     */
    String getFileContent(OperationContext context, BranchName branch, String filePath) throws Exception;

    /**
     * Searches for files matching a query.
     * Useful for finding configuration files, tests, documentation, etc.
     *
     * @param context Operation context
     * @param query   Search query (language-specific: GitHub uses v3 search syntax)
     * @return List of matching file paths
     * @throws Exception if the operation fails
     */
    List<String> searchFiles(OperationContext context, String query) throws Exception;

    /**
     * Lists open pull requests in the repository.
     *
     * @param context Operation context
     * @return List of open pull requests
     * @throws Exception if the operation fails
     */
    List<PullRequestSummary> listPullRequests(OperationContext context) throws Exception;

    /**
     * Downloads the entire repository as a zip archive and extracts it locally.
     *
     * @param context   Operation context
     * @param branch    Branch to download
     * @param outputDir Parent directory for extraction
     * @return Path to the extracted repository root
     * @throws Exception if download or extraction fails
     */
    java.nio.file.Path downloadArchive(OperationContext context, BranchName branch, java.nio.file.Path outputDir)
            throws Exception;

    /**
     * Summary of a pull request (read-only view).
     */
    record PullRequestSummary(String id, String url, BranchName branch, String title) {
    }
}
