package com.teasui.crm.auth.repository;

import com.teasui.crm.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsernameAndTenantId(String username, String tenantId);

    Optional<User> findByEmailAndTenantId(String email, String tenantId);

    boolean existsByUsernameAndTenantId(String username, String tenantId);

    boolean existsByEmailAndTenantId(String email, String tenantId);

    long countByTenantId(String tenantId);
}
