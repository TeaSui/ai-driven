package com.aidriven.core.source;

import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;

import java.util.List;

/**
 * Platform-agnostic unified interface for source control operations.
 * Extends segregated read/write interfaces per SOLID (Interface Segregation
 * Principle).
 */
public interface SourceControlClient extends RepositoryReader, RepositoryWriter {

        /** Gets the default branch. */
        BranchName getDefaultBranch(OperationContext context) throws Exception;

        /** Creates a new branch. */
        void createBranch(OperationContext context, BranchName branchName, BranchName fromBranch) throws Exception;

        /** Commits files. */
        String pushFiles(OperationContext context, BranchName branchName,
                        List<com.aidriven.spi.provider.SourceControlProvider.RepoFile> files, String commitMessage)
                        throws Exception;

        /** Creates a pull request. */
        SourceControlProvider.PullRequestResult openPullRequest(OperationContext context, String title,
                        String description, BranchName sourceBranch, BranchName destinationBranch) throws Exception;

        /** Gets the file tree. */
        List<String> getFileTree(OperationContext context, BranchName branch, String path) throws Exception;

        /** Gets file content. */
        String getFileContent(OperationContext context, BranchName branch, String filePath) throws Exception;

        /** Searches for files. */
        List<String> searchFiles(OperationContext context, String query) throws Exception;

        /** Adds a comment to a Pull Request. */
        void addPrComment(OperationContext context, String repoOwner, String repoSlug, String prNumber, String body);

        /** Replies to an existing PR comment. */
        void addPrCommentReply(OperationContext context, String repoOwner, String repoSlug, String prNumber,
                        String parentCommentId, String body);

        /**
         * Fetches CI workflow logs.
         *
         * @param context     The operation context
         * @param runId       The CI run identifier
         * @param maxLogChars The maximum amount of characters to extract. If null, a
         *                    default limit is used (e.g. 100k chars).
         * @return The text content of the logs
         */
        String getWorkflowRunLogs(OperationContext context, String runId, Integer maxLogChars) throws Exception;
}
