package com.aidriven.lambda.platform;

import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.lambda.model.AgentTask;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * GitHub-specific platform strategy.
 *
 * <p>All responses (progress, final, failure) are delivered as GitHub PR
 * comment replies using {@code in_reply_to_id}, creating a true threaded
 * conversation in the PR comment section.
 */
@Slf4j
public class GitHubPlatformStrategy implements PlatformStrategy {

    @Override
    public String platform() {
        return "GITHUB";
    }

    @Override
    public ProgressTracker createProgressTracker(AgentTask task, SourceControlClient sc) {
        String prNum = task.getPrNumber() != null ? task.getPrNumber() : task.getTicketKey();
        String commentId = task.getGithubCommentId();

        return new ProgressTracker() {
            @Override
            public void updateProgress(String ackId, List<ToolResult> results) {
                try {
                    StringBuilder progress = new StringBuilder("🔄 **AI Progress Update**\n\n");
                    for (ToolResult result : results) {
                        progress.append("- Executed: `").append(result.toolUseId()).append("`\n");
                    }
                    reply(sc, task, prNum, commentId, progress.toString());
                } catch (Exception e) {
                    log.warn("Failed to post GitHub progress update for PR={}: {}", prNum, e.getMessage());
                }
            }

            @Override
            public void complete(String ackId, String finalResponse) {
                try {
                    reply(sc, task, prNum, commentId, "✅ **AI Task Completed**\n\n" + finalResponse);
                } catch (Exception e) {
                    log.warn("Failed to post GitHub completion for PR={}: {}", prNum, e.getMessage());
                }
            }

            @Override
            public void fail(String ackId, String errorMessage) {
                try {
                    reply(sc, task, prNum, commentId, "❌ **AI Task Failed**\n\n" + errorMessage);
                } catch (Exception e) {
                    log.warn("Failed to post GitHub failure for PR={}: {}", prNum, e.getMessage());
                }
            }
        };
    }

    /**
     * GitHub responses are fully handled by the {@code ProgressTracker.complete()} path
     * (which posts the threaded reply), so this is a no-op.
     */
    @Override
    public void postFinalResponse(AgentTask task, SourceControlClient sc, String formattedResponse) {
        // GitHub response is handled entirely by ProgressTracker.complete() above
        log.debug("GitHub postFinalResponse skipped — handled by ProgressTracker for PR={}",
                task.getPrNumber());
    }

    private void reply(SourceControlClient sc, AgentTask task, String prNum,
            String parentCommentId, String body) throws Exception {
        String repoOwner = task.getRepoOwner();
        String repoSlug = task.getRepoSlug();
        if (parentCommentId != null && !parentCommentId.isBlank()) {
            sc.addPrCommentReply(task.getContext(), repoOwner, repoSlug, prNum, parentCommentId, body);
        } else {
            sc.addPrComment(task.getContext(), repoOwner, repoSlug, prNum, body);
        }
    }
}
