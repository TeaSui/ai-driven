package com.aidriven.core.source;

import com.aidriven.core.model.AgentResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Platform-agnostic interface for source control operations.
 * Implementations include Bitbucket Cloud and GitHub.
 */
public interface SourceControlClient {

    /**
     * Gets the default branch of the repository (e.g., "main", "master").
     */
    String getDefaultBranch() throws Exception;

    /**
     * Creates a new branch from an existing branch.
     *
     * @param branchName The new branch name
     * @param fromBranch The source branch to branch from
     */
    void createBranch(String branchName, String fromBranch) throws Exception;

    /**
     * Commits files to a branch.
     *
     * @param branchName    The target branch
     * @param files         The files to commit
     * @param commitMessage The commit message
     * @return A commit identifier (hash or status)
     */
    String commitFiles(String branchName, List<AgentResult.GeneratedFile> files, String commitMessage) throws Exception;

    /**
     * Creates a pull request (or merge request).
     *
     * @param title             PR title
     * @param description       PR description
     * @param sourceBranch      The source/feature branch
     * @param destinationBranch The target/base branch
     * @return Result containing PR ID, URL, and branch name
     */
    PullRequestResult createPullRequest(String title, String description, String sourceBranch,
            String destinationBranch) throws Exception;

    /**
     * Downloads the full repository as a zip archive and extracts it to disk.
     *
     * @param branch    The branch to download
     * @param outputDir The parent directory for extraction
     * @return Path to the extracted repository root directory
     */
    Path downloadArchive(String branch, Path outputDir) throws Exception;

    /**
     * Gets the file tree (list of all file paths) from a branch.
     *
     * @param branch The branch name
     * @param path   Optional path prefix to filter (null for root)
     * @return List of file paths
     */
    List<String> getFileTree(String branch, String path) throws Exception;

    /**
     * Gets the content of a single file from a branch.
     *
     * @param branch   The branch name
     * @param filePath The path to the file
     * @return The file content as a string, or null if not found
     */
    String getFileContent(String branch, String filePath) throws Exception;

    /**
     * Searches for files matching a query in the repository.
     *
     * @param query The search query
     * @return List of matching file paths
     */
    List<String> searchFiles(String query) throws Exception;

    /**
     * Result of creating a pull request.
     */
    record PullRequestResult(String id, String url, String branchName) {
    }
}
