package com.teasui.crm.auth.service;

import com.teasui.crm.auth.domain.Tenant;
import com.teasui.crm.auth.domain.User;
import com.teasui.crm.auth.dto.AuthResponse;
import com.teasui.crm.auth.dto.LoginRequest;
import com.teasui.crm.auth.dto.RegisterRequest;
import com.teasui.crm.auth.repository.TenantRepository;
import com.teasui.crm.auth.repository.UserRepository;
import com.teasui.crm.common.event.auth.UserAuthEvent;
import com.teasui.crm.common.exception.ServiceException;
import com.teasui.crm.common.messaging.RabbitMQConfig;
import com.teasui.crm.common.security.JwtTokenUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;
    private static final String SERVICE_NAME = "auth-service";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenUtil jwtTokenUtil;
    private final RabbitTemplate rabbitTemplate;

    @Value("${jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.getTenantSlug())
                .orElseThrow(() -> ServiceException.notFound("Tenant", request.getTenantSlug()));

        if (tenant.getStatus() != Tenant.TenantStatus.ACTIVE) {
            throw ServiceException.forbidden("Tenant account is not active");
        }

        User user = userRepository.findByUsernameAndTenantId(request.getUsername(), tenant.getId())
                .orElseThrow(() -> ServiceException.unauthorized("Invalid credentials"));

        if (user.isLocked()) {
            throw ServiceException.forbidden("Account is temporarily locked. Please try again later.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(user);
            throw ServiceException.unauthorized("Invalid credentials");
        }

        // Reset failed attempts on successful login
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        List<String> roles = new ArrayList<>(user.getRoles());
        String accessToken = jwtTokenUtil.generateToken(user.getId(), user.getUsername(), tenant.getId(), roles);

        publishAuthEvent(user, tenant.getId(), UserAuthEvent.AuthAction.LOGIN);

        log.info("User '{}' logged in successfully for tenant '{}'", user.getUsername(), tenant.getSlug());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtExpirationMs / 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .tenantId(tenant.getId())
                .roles(roles)
                .build();
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.getTenantSlug())
                .orElseThrow(() -> ServiceException.notFound("Tenant", request.getTenantSlug()));

        if (userRepository.existsByUsernameAndTenantId(request.getUsername(), tenant.getId())) {
            throw ServiceException.conflict("Username already exists in this tenant");
        }

        if (userRepository.existsByEmailAndTenantId(request.getEmail(), tenant.getId())) {
            throw ServiceException.conflict("Email already registered in this tenant");
        }

        long currentUserCount = userRepository.countByTenantId(tenant.getId());
        if (currentUserCount >= tenant.getMaxUsers()) {
            throw ServiceException.forbidden("Maximum user limit reached for this tenant");
        }

        User user = User.builder()
                .tenantId(tenant.getId())
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .roles(Set.of("ROLE_USER"))
                .build();

        user = userRepository.save(user);

        List<String> roles = new ArrayList<>(user.getRoles());
        String accessToken = jwtTokenUtil.generateToken(user.getId(), user.getUsername(), tenant.getId(), roles);

        log.info("User '{}' registered successfully for tenant '{}'", user.getUsername(), tenant.getSlug());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtExpirationMs / 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .tenantId(tenant.getId())
                .roles(roles)
                .build();
    }

    private void handleFailedLogin(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
            log.warn("User '{}' account locked after {} failed attempts", user.getUsername(), attempts);
        }

        userRepository.save(user);
    }

    private void publishAuthEvent(User user, String tenantId, UserAuthEvent.AuthAction action) {
        try {
            UserAuthEvent event = UserAuthEvent.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .tenantId(tenantId)
                    .action(action)
                    .build();
            event.initDefaults(SERVICE_NAME);

            String routingKey = action == UserAuthEvent.AuthAction.LOGIN
                    ? RabbitMQConfig.USER_LOGIN_KEY
                    : RabbitMQConfig.USER_LOGOUT_KEY;

            rabbitTemplate.convertAndSend(RabbitMQConfig.AUTH_EXCHANGE, routingKey, event);
        } catch (Exception e) {
            log.error("Failed to publish auth event: {}", e.getMessage());
        }
    }
}
