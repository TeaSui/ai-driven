package com.teasui.crm.notification.repository;

import com.teasui.crm.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    Page<Notification> findByRecipientUserIdAndTenantId(String userId, String tenantId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndTenantIdAndStatus(
            String userId, String tenantId, Notification.NotificationStatus status, Pageable pageable);

    Optional<Notification> findByIdAndTenantId(String id, String tenantId);

    long countByRecipientUserIdAndTenantIdAndStatus(
            String userId, String tenantId, Notification.NotificationStatus status);

    @Modifying
    @Query("UPDATE Notification n SET n.status = 'READ', n.readAt = CURRENT_TIMESTAMP " +
           "WHERE n.recipientUserId = :userId AND n.tenantId = :tenantId AND n.status = 'SENT'")
    int markAllAsRead(String userId, String tenantId);
}
