package com.aidriven.spi.provider;

import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * SPI for Source Control management (GitHub, Bitbucket, GitLab).
 */
public interface SourceControlProvider {
        /** Returns the unique identifier for this provider (e.g., "github"). */
        String getName();

        /**
         * Checks if this provider can handle the given repository URL or identifier.
         */
        boolean supports(String repositoryUri);

        /** Gets the default branch. */
        BranchName getDefaultBranch(OperationContext context) throws Exception;

        /** Creates a new branch. */
        void createBranch(OperationContext context, BranchName branchName, BranchName fromBranch) throws Exception;

        /** Commits files. */
        String pushFiles(OperationContext context, BranchName branchName, List<RepoFile> files, String commitMessage)
                        throws Exception;

        /** Creates a pull request. */
        PullRequestResult openPullRequest(OperationContext context, String title, String description,
                        BranchName sourceBranch, BranchName destinationBranch) throws Exception;

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

        record RepoFile(String path, String content, String operation) {
        }

        record PullRequestResult(String id, String url, BranchName branch, String title) {
        }
}
