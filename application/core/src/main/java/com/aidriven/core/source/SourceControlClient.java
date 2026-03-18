package com.aidriven.core.source;

import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;

import java.util.List;

/**
 * Platform-agnostic unified interface for source control operations.
 *
 * <p>This interface combines read operations (from {@link RepositoryReader}) and
 * write operations (from {@link RepositoryWriter}) into a single facade, while
 * also exposing additional operations that span both concerns (e.g., CI log
 * retrieval, direct PR commenting with owner/slug parameters).</p>
 *
 * <h3>Interface Segregation</h3>
 * <p>Callers that only need read access should depend on {@link RepositoryReader};
 * callers that only need write access should depend on {@link RepositoryWriter}.
 * This interface is for components that need the full set of operations (e.g.,
 * {@code SourceControlToolProvider}).</p>
 *
 * <h3>Known Implementations</h3>
 * <ul>
 *   <li>{@code GitHubClient} -- GitHub REST API (v3) implementation</li>
 *   <li>{@code BitbucketClient} -- Bitbucket Cloud REST API (v2) implementation</li>
 * </ul>
 *
 * <h3>Multi-Tenancy</h3>
 * <p>All operations accept an {@link OperationContext} that carries tenant,
 * repository, and tracing information. Implementations resolve credentials
 * and repository URLs from this context.</p>
 *
 * @see RepositoryReader
 * @see RepositoryWriter
 * @see SourceControlProvider
 * @since 1.0
 */
public interface SourceControlClient extends RepositoryReader, RepositoryWriter {

        /**
         * Gets the default branch name for the repository (typically
         * {@code "main"} or {@code "master"}).
         *
         * @param context the operation context for tenant and repository resolution
         * @return the default branch name; never {@code null}
         * @throws Exception if the API call fails (network, authentication, or
         *                   permission errors)
         */
        BranchName getDefaultBranch(OperationContext context) throws Exception;

        /**
         * Creates a new branch from an existing branch.
         *
         * <p>The new branch is created at the same commit as {@code fromBranch}.
         * If the branch already exists, the behavior is platform-dependent (some
         * implementations throw, others are idempotent).</p>
         *
         * @param context    the operation context
         * @param branchName the name of the new branch to create
         * @param fromBranch the existing branch to branch from (its HEAD commit
         *                   becomes the new branch's starting point)
         * @throws Exception if the branch cannot be created (already exists,
         *                   permission denied, source branch not found)
         */
        void createBranch(OperationContext context, BranchName branchName, BranchName fromBranch) throws Exception;

        /**
         * Pushes (commits) files to the specified branch.
         *
         * <p>Each {@link SourceControlProvider.RepoFile} specifies a file path,
         * content, and operation type ({@code "create"}, {@code "update"}, or
         * {@code "delete"}). All files are committed atomically in a single commit.</p>
         *
         * @param context       the operation context
         * @param branchName    the target branch to commit to
         * @param files         the files to commit, each with path, content, and
         *                      operation type
         * @param commitMessage the Git commit message
         * @return the commit SHA/ID of the created commit
         * @throws Exception if the commit fails (branch not found, conflict,
         *                   permission denied)
         */
        String pushFiles(OperationContext context, BranchName branchName,
                        List<com.aidriven.spi.provider.SourceControlProvider.RepoFile> files, String commitMessage)
                        throws Exception;

        /**
         * Creates a pull request from a source branch to a destination branch.
         *
         * @param context           the operation context
         * @param title             the pull request title
         * @param description       the pull request description (supports markdown)
         * @param sourceBranch      the source (feature) branch
         * @param destinationBranch the destination (target) branch
         * @return a {@link SourceControlProvider.PullRequestResult} containing the
         *         PR ID, URL, branch, and title
         * @throws Exception if the PR cannot be created (branch not found, PR
         *                   already exists, permission denied)
         */
        SourceControlProvider.PullRequestResult openPullRequest(OperationContext context, String title,
                        String description, BranchName sourceBranch, BranchName destinationBranch) throws Exception;

        /**
         * Lists files in the repository tree at the given path on the specified
         * branch.
         *
         * @param context the operation context
         * @param branch  the branch to query
         * @param path    the directory path to list (relative to repository root);
         *                use {@code ""} or {@code "/"} for root
         * @return a list of file paths relative to the repository root
         * @throws Exception if the operation fails
         */
        List<String> getFileTree(OperationContext context, BranchName branch, String path) throws Exception;

        /**
         * Retrieves the content of a single file from the repository.
         *
         * @param context  the operation context
         * @param branch   the branch to read from
         * @param filePath the file path relative to the repository root
         * @return the file content as a string, or {@code null} if the file does
         *         not exist
         * @throws Exception if the operation fails (network, permission errors)
         */
        String getFileContent(OperationContext context, BranchName branch, String filePath) throws Exception;

        /**
         * Searches for files matching the given query string.
         *
         * <p>The query syntax is platform-specific (e.g., GitHub uses its code
         * search API v3 syntax). Results are limited by the platform's search API
         * constraints.</p>
         *
         * @param context the operation context
         * @param query   the search query string
         * @return a list of matching file paths; empty if no matches found
         * @throws Exception if the search fails
         */
        List<String> searchFiles(OperationContext context, String query) throws Exception;

        /**
         * Adds a top-level comment to a pull request.
         *
         * <p>This variant accepts explicit repository owner and slug parameters,
         * which is useful when the PR belongs to a different repository than the
         * one in the operation context.</p>
         *
         * @param context   the operation context
         * @param repoOwner the repository owner (user or organization)
         * @param repoSlug  the repository slug/name
         * @param prNumber  the pull request number or ID
         * @param body      the comment body (supports markdown)
         */
        void addPrComment(OperationContext context, String repoOwner, String repoSlug, String prNumber, String body);

        /**
         * Replies to an existing comment on a pull request, creating a threaded
         * response.
         *
         * @param context         the operation context
         * @param repoOwner       the repository owner (user or organization)
         * @param repoSlug        the repository slug/name
         * @param prNumber        the pull request number or ID
         * @param parentCommentId the ID of the comment to reply to
         * @param body            the reply body (supports markdown)
         */
        void addPrCommentReply(OperationContext context, String repoOwner, String repoSlug, String prNumber,
                        String parentCommentId, String body);

        /**
         * Fetches CI/CD workflow run logs for a given run identifier.
         *
         * <p>The logs are returned as plain text. If the logs exceed
         * {@code maxLogChars}, they are truncated from the beginning (keeping
         * the most recent output). This is useful for diagnosing build failures
         * or test results.</p>
         *
         * @param context     the operation context
         * @param runId       the CI workflow run identifier (platform-specific,
         *                    e.g., GitHub Actions run ID)
         * @param maxLogChars the maximum number of characters to extract; if
         *                    {@code null}, a platform-specific default limit is
         *                    used (typically 100,000 chars)
         * @return the text content of the workflow logs
         * @throws Exception if the logs cannot be retrieved (run not found,
         *                   permission denied, or network errors)
         */
        String getWorkflowRunLogs(OperationContext context, String runId, Integer maxLogChars) throws Exception;
}
