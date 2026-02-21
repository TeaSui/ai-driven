package com.teasui.crm.workflow.repository;

import com.teasui.crm.workflow.domain.Workflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    Page<Workflow> findByTenantId(String tenantId, Pageable pageable);

    Optional<Workflow> findByIdAndTenantId(String id, String tenantId);

    boolean existsByNameAndTenantId(String name, String tenantId);

    long countByTenantId(String tenantId);

    Page<Workflow> findByTenantIdAndStatus(String tenantId, Workflow.WorkflowStatus status, Pageable pageable);
}
