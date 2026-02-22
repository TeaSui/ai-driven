package com.teasui.tenant.repository;

import com.teasui.tenant.domain.Tenant;
import com.teasui.tenant.domain.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findByAdminEmail(String adminEmail);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);

    List<Tenant> findAllByStatus(TenantStatus status);
}
