package com.aidriven.contracts.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Platform-agnostic contract for source control operations.
 * <p>
 * This interface lives in the contracts module so that custom integrations
 * (GitLab, Azure DevOps, etc.) can implement it without depending on core.
 * </p>
 *
 * <p>Existing implementations: BitbucketClient, GitHubClient.</p>
 */
public interface SourceControlOperations {

    /**
     * Gets the default branch of the repository (e.g., "main", "master").
     */
    String getDefaultBranch() throws Exception;

    /**
     * Creates a new branch from an existing branch.
     */
    void createBranch(String branchName, String fromBranch) throws Exception;

    /**
     * Commits files to a branch.
     *
     * @return A commit identifier (hash or status)
     */
    String commitFiles(String branchName, List<GeneratedFileData> files, String commitMessage) throws Exception;

    /**
     * Creates a pull request.
     *
     * @return Result containing PR ID, URL, and branch name
     */
    PullRequestInfo createPullRequest(String title, String description,
            String sourceBranch, String destinationBranch) throws Exception;

    /**
     * Downloads the full repository as an archive and extracts it.
     *
     * @return Path to the extracted repository root directory
     */
    Path downloadArchive(String branch, Path outputDir) throws Exception;

    /**
     * Gets the file tree (list of all file paths) from a branch.
     */
    List<String> getFileTree(String branch, String path) throws Exception;

    /**
     * Gets the content of a single file from a branch.
     *
     * @return The file content as a string, or null if not found
     */
    String getFileContent(String branch, String filePath) throws Exception;

    /**
     * Searches for files matching a query in the repository.
     */
    List<String> searchFiles(String query) throws Exception;

    /**
     * Result of creating a pull request.
     */
    record PullRequestInfo(String id, String url, String branchName) {
    }

    /**
     * Represents a file to be committed.
     */
    record GeneratedFileData(String path, String content, String operation) {
    }
}
