package com.teasui.crm.workflow.repository;

import com.teasui.crm.workflow.domain.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, String> {

    Page<WorkflowExecution> findByWorkflowIdAndTenantId(String workflowId, String tenantId, Pageable pageable);

    Optional<WorkflowExecution> findByIdAndTenantId(String id, String tenantId);

    long countByWorkflowIdAndStatus(String workflowId, WorkflowExecution.ExecutionStatus status);
}
