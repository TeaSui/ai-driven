package com.teasui.crm.auth.service;

import com.teasui.crm.auth.domain.Tenant;
import com.teasui.crm.auth.domain.User;
import com.teasui.crm.auth.dto.AuthResponse;
import com.teasui.crm.auth.dto.LoginRequest;
import com.teasui.crm.auth.dto.RegisterRequest;
import com.teasui.crm.auth.repository.TenantRepository;
import com.teasui.crm.auth.repository.UserRepository;
import com.teasui.crm.common.exception.ServiceException;
import com.teasui.crm.common.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenUtil jwtTokenUtil;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private AuthService authService;

    private Tenant activeTenant;
    private User activeUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "jwtExpirationMs", 86400000L);

        activeTenant = Tenant.builder()
                .id("tenant-001")
                .name("Test Company")
                .slug("test-company")
                .contactEmail("admin@test.com")
                .status(Tenant.TenantStatus.ACTIVE)
                .maxUsers(10)
                .build();

        activeUser = User.builder()
                .id("user-001")
                .tenantId("tenant-001")
                .username("testuser")
                .email("test@test.com")
                .passwordHash("$2a$10$hashedpassword")
                .status(User.UserStatus.ACTIVE)
                .roles(Set.of("ROLE_USER"))
                .failedLoginAttempts(0)
                .build();
    }

    @Test
    @DisplayName("Should login successfully with valid credentials")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));
        when(userRepository.findByUsernameAndTenantId("testuser", "tenant-001"))
                .thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("password123", activeUser.getPasswordHash())).thenReturn(true);
        when(userRepository.save(any(User.class))).thenReturn(activeUser);
        when(jwtTokenUtil.generateToken(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("mock-jwt-token");

        AuthResponse response = authService.login(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mock-jwt-token");
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getTenantId()).isEqualTo("tenant-001");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw unauthorized with invalid password")
    void shouldThrowUnauthorizedWithInvalidPassword() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrongpassword");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));
        when(userRepository.findByUsernameAndTenantId("testuser", "tenant-001"))
                .thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("wrongpassword", activeUser.getPasswordHash())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(activeUser);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    @DisplayName("Should throw not found when tenant does not exist")
    void shouldThrowNotFoundWhenTenantDoesNotExist() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setTenantSlug("non-existent");

        when(tenantRepository.findBySlug("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("Should throw forbidden when tenant is suspended")
    void shouldThrowForbiddenWhenTenantIsSuspended() {
        activeTenant.setStatus(Tenant.TenantStatus.SUSPENDED);

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("Should register user successfully")
    void shouldRegisterUserSuccessfully() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("new@test.com");
        request.setPassword("password123");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));
        when(userRepository.existsByUsernameAndTenantId(anyString(), anyString())).thenReturn(false);
        when(userRepository.existsByEmailAndTenantId(anyString(), anyString())).thenReturn(false);
        when(userRepository.countByTenantId(anyString())).thenReturn(2L);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashedpassword");
        when(userRepository.save(any(User.class))).thenReturn(activeUser);
        when(jwtTokenUtil.generateToken(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("mock-jwt-token");

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mock-jwt-token");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw conflict when username already exists")
    void shouldThrowConflictWhenUsernameExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existinguser");
        request.setEmail("new@test.com");
        request.setPassword("password123");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));
        when(userRepository.existsByUsernameAndTenantId(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    @DisplayName("Should throw forbidden when max users reached")
    void shouldThrowForbiddenWhenMaxUsersReached() {
        activeTenant.setMaxUsers(2);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("new@test.com");
        request.setPassword("password123");
        request.setTenantSlug("test-company");

        when(tenantRepository.findBySlug("test-company")).thenReturn(Optional.of(activeTenant));
        when(userRepository.existsByUsernameAndTenantId(anyString(), anyString())).thenReturn(false);
        when(userRepository.existsByEmailAndTenantId(anyString(), anyString())).thenReturn(false);
        when(userRepository.countByTenantId(anyString())).thenReturn(2L);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Maximum user limit");
    }
}
