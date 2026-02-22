package com.teasui.integration.repository;

import com.teasui.integration.domain.Integration;
import com.teasui.integration.domain.IntegrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationRepository extends JpaRepository<Integration, String> {

    List<Integration> findAllByTenantId(String tenantId);

    List<Integration> findAllByTenantIdAndStatus(String tenantId, IntegrationStatus status);

    Optional<Integration> findByTenantIdAndId(String tenantId, String id);

    boolean existsByTenantIdAndProviderAndName(String tenantId, String provider, String name);
}
