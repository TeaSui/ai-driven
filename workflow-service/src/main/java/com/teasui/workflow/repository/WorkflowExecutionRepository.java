package com.teasui.workflow.repository;

import com.teasui.workflow.domain.ExecutionStatus;
import com.teasui.workflow.domain.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, String> {

    Page<WorkflowExecution> findAllByTenantIdAndWorkflowDefinitionId(
            String tenantId, String workflowDefinitionId, Pageable pageable);

    List<WorkflowExecution> findAllByTenantIdAndStatus(String tenantId, ExecutionStatus status);

    long countByTenantIdAndWorkflowDefinitionIdAndStatus(
            String tenantId, String workflowDefinitionId, ExecutionStatus status);
}
