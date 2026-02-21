package com.teasui.crm.notification.controller;

import com.teasui.crm.common.dto.ApiResponse;
import com.teasui.crm.notification.domain.Notification;
import com.teasui.crm.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<Notification>>> getUserNotifications(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Notification> notifications = notificationService.getUserNotifications(tenantId, userId, page, size);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId) {
        long count = notificationService.getUnreadCount(tenantId, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("unreadCount", count)));
    }

    @PutMapping("/mark-all-read")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId) {
        int updated = notificationService.markAllAsRead(tenantId, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("markedAsRead", updated)));
    }
}
