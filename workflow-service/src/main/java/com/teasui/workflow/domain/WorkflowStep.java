package com.teasui.workflow.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    private String stepId;
    private String name;
    private String type;
    private int order;
    private Map<String, Object> config;
    private String nextStepId;
    private String failureStepId;
    private boolean retryOnFailure;
    private int maxRetries;
    private long timeoutSeconds;
}
