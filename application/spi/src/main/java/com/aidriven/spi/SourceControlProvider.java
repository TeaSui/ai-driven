package com.aidriven.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * Service Provider Interface for source control integrations.
 * Implementations can wrap Bitbucket, GitHub, GitLab, Azure DevOps, etc.
 *
 * <p>Each tenant may use a different source control platform.</p>
 */
public interface SourceControlProvider {

    /**
     * Unique identifier for this provider (e.g., "bitbucket", "github", "gitlab").
     */
    String providerId();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * Gets the default branch of the repository.
     */
    String getDefaultBranch() throws Exception;

    /**
     * Creates a new branch from an existing branch.
     */
    void createBranch(String branchName, String fromBranch) throws Exception;

    /**
     * Commits files to a branch.
     *
     * @return A commit identifier
     */
    String commitFiles(String branchName, List<FileToCommit> files, String commitMessage) throws Exception;

    /**
     * Creates a pull request.
     */
    PullRequestInfo createPullRequest(String title, String description,
                                       String sourceBranch, String destinationBranch) throws Exception;

    /**
     * Downloads the repository archive.
     */
    Path downloadArchive(String branch, Path outputDir) throws Exception;

    /**
     * Gets the file tree from a branch.
     */
    List<String> getFileTree(String branch, String path) throws Exception;

    /**
     * Gets file content from a branch.
     */
    String getFileContent(String branch, String filePath) throws Exception;

    /**
     * Searches for files matching a query.
     */
    List<String> searchFiles(String query) throws Exception;

    /**
     * Returns a new provider instance targeting a different repository.
     */
    SourceControlProvider withRepository(String owner, String repo);

    /**
     * File to be committed.
     */
    record FileToCommit(String path, String content, String operation) {}

    /**
     * Result of creating a pull request.
     */
    record PullRequestInfo(String id, String url, String branchName) {}
}
