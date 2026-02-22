package com.teasui.workflow.repository;

import com.teasui.workflow.domain.WorkflowDefinition;
import com.teasui.workflow.domain.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {

    List<WorkflowDefinition> findAllByTenantId(String tenantId);

    List<WorkflowDefinition> findAllByTenantIdAndStatus(String tenantId, WorkflowStatus status);

    Optional<WorkflowDefinition> findByTenantIdAndId(String tenantId, String id);

    boolean existsByTenantIdAndNameAndVersion(String tenantId, String name, Integer version);
}
