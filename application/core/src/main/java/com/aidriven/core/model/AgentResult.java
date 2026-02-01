package com.aidriven.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result from an AI agent's processing of a ticket.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    
    private String ticketId;
    private AgentType agentType;
    private boolean success;
    private String errorMessage;
    
    /**
     * Generated code files.
     */
    private List<GeneratedFile> generatedFiles;
    
    /**
     * Commit message for the changes.
     */
    private String commitMessage;
    
    /**
     * PR title.
     */
    private String prTitle;
    
    /**
     * PR description.
     */
    private String prDescription;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedFile {
        private String path;
        private String content;
        private FileOperation operation;
    }
    
    public enum FileOperation {
        CREATE,
        UPDATE,
        DELETE
    }
}
