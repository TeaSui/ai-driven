package com.teasui.crm.notification.service;

import com.teasui.crm.common.event.notification.NotificationEvent;
import com.teasui.crm.common.event.workflow.WorkflowExecutionEvent;
import com.teasui.crm.notification.domain.Notification;
import com.teasui.crm.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.teasui.crm.common.messaging.RabbitMQConfig.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;

    @RabbitListener(queues = NOTIFICATION_EMAIL_QUEUE)
    public void handleEmailNotification(NotificationEvent event) {
        log.info("Received email notification event: {}", event.getEventId());
        if (event.getRecipientEmails() != null) {
            event.getRecipientEmails().forEach(email ->
                    sendEmail(event.getTenantId(), event.getRecipientUserId(),
                            email, event.getSubject(), event.getBody()));
        }
    }

    @RabbitListener(queues = NOTIFICATION_IN_APP_QUEUE)
    @Transactional
    public void handleInAppNotification(NotificationEvent event) {
        log.info("Received in-app notification event: {}", event.getEventId());
        Notification notification = Notification.builder()
                .tenantId(event.getTenantId())
                .recipientUserId(event.getRecipientUserId())
                .type(Notification.NotificationType.valueOf(event.getType().name()))
                .channel(Notification.NotificationChannel.IN_APP)
                .subject(event.getSubject())
                .body(event.getBody())
                .priority(Notification.Priority.valueOf(event.getPriority().name()))
                .status(Notification.NotificationStatus.SENT)
                .sentAt(LocalDateTime.now())
                .build();
        notificationRepository.save(notification);
    }

    @RabbitListener(queues = WORKFLOW_QUEUE)
    public void handleWorkflowEvent(WorkflowExecutionEvent event) {
        log.info("Received workflow event: {} - {}", event.getWorkflowName(), event.getStatus());

        if (event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.COMPLETED ||
                event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.FAILED) {

            Notification.NotificationType type = event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.COMPLETED
                    ? Notification.NotificationType.WORKFLOW_COMPLETED
                    : Notification.NotificationType.WORKFLOW_FAILED;

            String subject = String.format("Workflow '%s' %s",
                    event.getWorkflowName(),
                    event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.COMPLETED ? "completed" : "failed");

            String body = event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.FAILED
                    ? String.format("Workflow '%s' failed: %s", event.getWorkflowName(), event.getErrorMessage())
                    : String.format("Workflow '%s' completed successfully.", event.getWorkflowName());

            Notification notification = Notification.builder()
                    .tenantId(event.getTenantId())
                    .recipientUserId(event.getTriggeredBy())
                    .type(type)
                    .channel(Notification.NotificationChannel.IN_APP)
                    .subject(subject)
                    .body(body)
                    .priority(event.getStatus() == WorkflowExecutionEvent.ExecutionStatus.FAILED
                            ? Notification.Priority.HIGH : Notification.Priority.MEDIUM)
                    .status(Notification.NotificationStatus.SENT)
                    .sentAt(LocalDateTime.now())
                    .build();

            notificationRepository.save(notification);
        }
    }

    @Async
    public void sendEmail(String tenantId, String userId, String email, String subject, String body) {
        Notification notification = Notification.builder()
                .tenantId(tenantId)
                .recipientUserId(userId)
                .recipientEmail(email)
                .type(Notification.NotificationType.CUSTOM)
                .channel(Notification.NotificationChannel.EMAIL)
                .subject(subject)
                .body(body)
                .status(Notification.NotificationStatus.PENDING)
                .build();

        notification = notificationRepository.save(notification);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);

            notification.setStatus(Notification.NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            log.info("Email sent to '{}'", email);
        } catch (Exception e) {
            log.error("Failed to send email to '{}': {}", email, e.getMessage());
            notification.setStatus(Notification.NotificationStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
        }

        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public Page<Notification> getUserNotifications(String tenantId, String userId, int page, int size) {
        return notificationRepository.findByRecipientUserIdAndTenantId(
                userId, tenantId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String tenantId, String userId) {
        return notificationRepository.countByRecipientUserIdAndTenantIdAndStatus(
                userId, tenantId, Notification.NotificationStatus.SENT);
    }

    @Transactional
    public int markAllAsRead(String tenantId, String userId) {
        return notificationRepository.markAllAsRead(userId, tenantId);
    }
}
