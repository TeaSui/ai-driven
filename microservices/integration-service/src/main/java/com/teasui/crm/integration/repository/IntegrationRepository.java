package com.teasui.crm.integration.repository;

import com.teasui.crm.integration.domain.Integration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationRepository extends JpaRepository<Integration, String> {

    List<Integration> findByTenantId(String tenantId);

    Optional<Integration> findByIdAndTenantId(String id, String tenantId);

    List<Integration> findByTenantIdAndStatus(String tenantId, Integration.IntegrationStatus status);
}
