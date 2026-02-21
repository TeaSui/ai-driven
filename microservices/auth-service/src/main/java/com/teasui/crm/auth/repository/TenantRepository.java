package com.teasui.crm.auth.repository;

import com.teasui.crm.auth.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);
}
