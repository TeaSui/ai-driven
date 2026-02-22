package com.teasui.tenant.service;

import com.teasui.common.event.TenantEvent;
import com.teasui.common.exception.ServiceException;
import com.teasui.tenant.domain.SubscriptionPlan;
import com.teasui.tenant.domain.Tenant;
import com.teasui.tenant.domain.TenantStatus;
import com.teasui.tenant.dto.CreateTenantRequest;
import com.teasui.tenant.dto.TenantResponse;
import com.teasui.tenant.mapper.TenantMapper;
import com.teasui.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private KafkaTemplate<String, TenantEvent> kafkaTemplate;

    @InjectMocks
    private TenantService tenantService;

    private CreateTenantRequest createRequest;
    private Tenant tenant;
    private TenantResponse tenantResponse;

    @BeforeEach
    void setUp() {
        createRequest = CreateTenantRequest.builder()
                .name("Acme Corp")
                .adminEmail("admin@acme.com")
                .plan(SubscriptionPlan.PROFESSIONAL)
                .build();

        tenant = Tenant.builder()
                .id("tenant-123")
                .name("Acme Corp")
                .slug("acme-corp")
                .adminEmail("admin@acme.com")
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(TenantStatus.ACTIVE)
                .maxUsers(25)
                .maxWorkflows(100)
                .build();

        tenantResponse = TenantResponse.builder()
                .id("tenant-123")
                .name("Acme Corp")
                .slug("acme-corp")
                .adminEmail("admin@acme.com")
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(TenantStatus.ACTIVE)
                .build();
    }

    @Test
    void createTenant_whenValidRequest_shouldCreateAndReturnTenant() {
        when(tenantRepository.existsByName(anyString())).thenReturn(false);
        when(tenantRepository.existsBySlug(anyString())).thenReturn(false);
        when(tenantMapper.toEntity(any(CreateTenantRequest.class))).thenReturn(tenant);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(tenant);
        when(tenantMapper.toResponse(any(Tenant.class))).thenReturn(tenantResponse);
        when(kafkaTemplate.send(anyString(), anyString(), any(TenantEvent.class))).thenReturn(null);

        TenantResponse result = tenantService.createTenant(createRequest);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Acme Corp");
        verify(tenantRepository).save(any(Tenant.class));
        verify(kafkaTemplate).send(eq("tenant-events"), anyString(), any(TenantEvent.class));
    }

    @Test
    void createTenant_whenNameAlreadyExists_shouldThrowConflict() {
        when(tenantRepository.existsByName("Acme Corp")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.createTenant(createRequest))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("already exists");

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void getTenantById_whenExists_shouldReturnTenant() {
        when(tenantRepository.findById("tenant-123")).thenReturn(Optional.of(tenant));
        when(tenantMapper.toResponse(tenant)).thenReturn(tenantResponse);

        TenantResponse result = tenantService.getTenantById("tenant-123");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("tenant-123");
    }

    @Test
    void getTenantById_whenNotFound_shouldThrowNotFound() {
        when(tenantRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantById("unknown"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void suspendTenant_whenActive_shouldSuspendAndPublishEvent() {
        when(tenantRepository.findById("tenant-123")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenReturn(tenant);
        when(tenantMapper.toResponse(any(Tenant.class))).thenReturn(tenantResponse);
        when(kafkaTemplate.send(anyString(), anyString(), any(TenantEvent.class))).thenReturn(null);

        tenantService.suspendTenant("tenant-123");

        verify(kafkaTemplate).send(eq("tenant-events"), eq("tenant-123"), any(TenantEvent.class));
    }

    @Test
    void suspendTenant_whenAlreadySuspended_shouldThrowBadRequest() {
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findById("tenant-123")).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> tenantService.suspendTenant("tenant-123"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("not active");
    }
}
