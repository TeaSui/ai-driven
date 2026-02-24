package com.aidriven.core.source;

import com.aidriven.core.model.AgentResult;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import lombok.NonNull;

import java.util.List;

/**
 * Write operations for source control repositories.
 * Segregated from read operations per Interface Segregation Principle (ISP).
 *
 * <p>
 * PSE-Grade Benefits:
 * - Single Responsibility: focused only on mutations
 * - Easier Testing: mock write operations independently
 * - Clear Intent: callers know this interface modifies state
 * - Transactional: supports future batch/transaction patterns
 * </p>
 *
 * Platform Implementations:
 * - GitHub: REST API write operations
 * - Bitbucket: REST API write operations
 *
 * @since 1.0
 */
public interface RepositoryWriter {

        /**
         * Creates a new branch in the repository.
         *
         * @param context    Operation context
         * @param branchName New branch name to create
         * @param fromBranch Existing branch to branch from
         * @throws Exception if the operation fails (already exists, permission, etc.)
         */
        void createBranch(OperationContext context, @NonNull BranchName branchName, @NonNull BranchName fromBranch)
                        throws Exception;

        /**
         * Commits files to the specified branch.
         * Creates or modifies files, then commits them with the provided message.
         *
         * @param context       Operation context
         * @param branchName    Target branch for commit
         * @param files         Files to commit (path + content pairs)
         * @param commitMessage Git commit message
         * @return The commit SHA/ID
         * @throws Exception if the operation fails (branch not found, permission, etc.)
         */
        String commitFiles(OperationContext context, @NonNull BranchName branchName,
                        @NonNull List<AgentResult.GeneratedFile> files, String commitMessage) throws Exception;

        /**
         * Creates a pull request from source branch to destination.
         *
         * @param context           Operation context
         * @param title             PR title
         * @param description       PR description (can include markdown)
         * @param sourceBranch      Source/feature branch
         * @param destinationBranch Destination/target branch
         * @return Pull request result with ID and URL
         * @throws Exception if the operation fails (branch not found, permission, etc.)
         */
        com.aidriven.spi.provider.SourceControlProvider.PullRequestResult createPullRequest(OperationContext context,
                        String title, String description,
                        @NonNull BranchName sourceBranch, @NonNull BranchName destinationBranch) throws Exception;

        /**
         * Adds a comment to a pull request.
         *
         * @param context  Operation context
         * @param prNumber Pull request number/ID
         * @param body     Comment text (supports markdown on most platforms)
         * @return The comment ID
         * @throws Exception if the operation fails (PR not found, permission, etc.)
         */
        String addPrComment(OperationContext context, String prNumber, String body) throws Exception;

        /**
         * Adds a reply to a specific comment on a pull request.
         * For threaded comments on platforms that support them.
         *
         * @param context         Operation context
         * @param prNumber        Pull request number/ID
         * @param parentCommentId ID of the comment being replied to
         * @param body            Reply text
         * @return The new comment ID
         * @throws Exception if the operation fails (comment not found, not supported,
         *                   etc.)
         */
        String addPrCommentReply(OperationContext context, String prNumber, String parentCommentId, String body)
                        throws Exception;

        /**
         * Result of creating a pull request.
         * Contains all information needed to reference the PR.
         */
        record PullRequestResult(String id, String url, BranchName branch, String title) {
        }
}
