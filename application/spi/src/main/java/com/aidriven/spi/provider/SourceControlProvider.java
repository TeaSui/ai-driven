package com.aidriven.spi.provider;

import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * Service Provider Interface (SPI) for source control platform backends.
 *
 * <p>This is the low-level provider contract that decouples the core agent
 * framework from specific source control platform APIs. Each implementation
 * wraps a platform's REST API and handles authentication, pagination, and
 * error mapping.</p>
 *
 * <h3>Known Implementations</h3>
 * <ul>
 *   <li>{@code GitHubSourceControlProvider} -- GitHub REST API (v3)</li>
 *   <li>{@code BitbucketSourceControlProvider} -- Bitbucket Cloud REST API (v2)</li>
 * </ul>
 *
 * <h3>Provider Selection</h3>
 * <p>The factory uses {@link #supports(String)} to determine which provider
 * handles a given repository. For multi-provider setups, providers are queried
 * in registration order and the first match wins.</p>
 *
 * <h3>Relationship to Core Interfaces</h3>
 * <p>The core module's {@link com.aidriven.core.source.SourceControlClient}
 * delegates to this SPI. The SPI lives in a separate module to avoid coupling
 * the core to platform-specific dependencies.</p>
 *
 * @see com.aidriven.spi.model.OperationContext
 * @see com.aidriven.spi.model.BranchName
 * @since 1.0
 */
public interface SourceControlProvider {

        /**
         * Returns the unique identifier for this provider.
         *
         * @return the provider name (e.g., {@code "github"}, {@code "bitbucket"});
         *         never {@code null}
         */
        String getName();

        /**
         * Checks whether this provider can handle the given repository URL or
         * identifier.
         *
         * <p>Used by the factory to route repository operations to the correct
         * provider. Implementations typically match on URL hostname patterns
         * (e.g., {@code github.com} or {@code bitbucket.org}).</p>
         *
         * @param repositoryUri the repository URL or identifier to test
         * @return {@code true} if this provider can handle the repository
         */
        boolean supports(String repositoryUri);

        /**
         * Gets the default branch name for the repository (typically
         * {@code "main"} or {@code "master"}).
         *
         * @param context the operation context for tenant and repository resolution
         * @return the default branch name; never {@code null}
         * @throws Exception if the API call fails
         */
        BranchName getDefaultBranch(OperationContext context) throws Exception;

        /**
         * Creates a new branch from an existing branch at its current HEAD commit.
         *
         * @param context    the operation context
         * @param branchName the name of the new branch to create
         * @param fromBranch the source branch to branch from
         * @throws Exception if the branch cannot be created
         */
        void createBranch(OperationContext context, BranchName branchName, BranchName fromBranch) throws Exception;

        /**
         * Commits a set of files to the specified branch atomically.
         *
         * @param context       the operation context
         * @param branchName    the target branch
         * @param files         the files to commit, each specifying path, content,
         *                      and operation type
         * @param commitMessage the Git commit message
         * @return the commit SHA/ID of the created commit
         * @throws Exception if the commit fails
         */
        String pushFiles(OperationContext context, BranchName branchName, List<RepoFile> files, String commitMessage)
                        throws Exception;

        /**
         * Creates a pull request from a source branch to a destination branch.
         *
         * @param context           the operation context
         * @param title             the pull request title
         * @param description       the pull request description (supports markdown)
         * @param sourceBranch      the source (feature) branch
         * @param destinationBranch the destination (target) branch
         * @return the result containing PR ID, URL, branch, and title
         * @throws Exception if the PR cannot be created
         */
        PullRequestResult openPullRequest(OperationContext context, String title, String description,
                        BranchName sourceBranch, BranchName destinationBranch) throws Exception;

        /**
         * Lists files in the repository tree at the given path and branch.
         *
         * @param context the operation context
         * @param branch  the branch to query
         * @param path    the directory path relative to the repository root
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
         * @return the file content as a string, or {@code null} if not found
         * @throws Exception if the operation fails
         */
        String getFileContent(OperationContext context, BranchName branch, String filePath) throws Exception;

        /**
         * Searches for files matching the given query string.
         *
         * <p>The query syntax is platform-specific.</p>
         *
         * @param context the operation context
         * @param query   the search query
         * @return a list of matching file paths; empty if no matches
         * @throws Exception if the search fails
         */
        List<String> searchFiles(OperationContext context, String query) throws Exception;

        /**
         * Adds a top-level comment to a pull request.
         *
         * @param context   the operation context
         * @param repoOwner the repository owner (user or organization)
         * @param repoSlug  the repository slug/name
         * @param prNumber  the pull request number or ID
         * @param body      the comment body (supports markdown)
         */
        void addPrComment(OperationContext context, String repoOwner, String repoSlug, String prNumber, String body);

        /**
         * Replies to an existing comment on a pull request, creating a
         * threaded response.
         *
         * @param context         the operation context
         * @param repoOwner       the repository owner
         * @param repoSlug        the repository slug/name
         * @param prNumber        the pull request number or ID
         * @param parentCommentId the ID of the comment to reply to
         * @param body            the reply body (supports markdown)
         */
        void addPrCommentReply(OperationContext context, String repoOwner, String repoSlug, String prNumber,
                        String parentCommentId, String body);

        /**
         * Represents a file to be committed to a repository.
         *
         * @param path      the file path relative to the repository root
         * @param content   the file content (for create/update operations)
         * @param operation the operation type: {@code "create"}, {@code "update"},
         *                  or {@code "delete"}
         */
        record RepoFile(String path, String content, String operation) {
        }

        /**
         * Encapsulates the result of creating a pull request.
         *
         * @param id     the platform-specific PR identifier
         * @param url    the web URL for viewing the PR in a browser
         * @param branch the source branch of the PR
         * @param title  the PR title
         */
        record PullRequestResult(String id, String url, BranchName branch, String title) {
        }
}
