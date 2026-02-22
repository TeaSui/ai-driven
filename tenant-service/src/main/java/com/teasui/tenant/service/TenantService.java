package com.teasui.tenant.service;

import com.teasui.common.exception.ServiceException;
import com.teasui.common.event.TenantEvent;
import com.teasui.tenant.domain.Tenant;
import com.teasui.tenant.domain.TenantStatus;
import com.teasui.tenant.dto.CreateTenantRequest;
import com.teasui.tenant.dto.TenantResponse;
import com.teasui.tenant.mapper.TenantMapper;
import com.teasui.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final String TENANT_EVENTS_TOPIC = "tenant-events";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9-]");
    private static final Pattern MULTIPLE_HYPHENS = Pattern.compile("-+");

    private final TenantRepository tenantRepository;
    private final TenantMapper tenantMapper;
    private final KafkaTemplate<String, TenantEvent> kafkaTemplate;

    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsByName(request.getName())) {
            throw ServiceException.conflict("Tenant with name '" + request.getName() + "' already exists");
        }

        String slug = generateSlug(request.getName());
        if (tenantRepository.existsBySlug(slug)) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        Tenant tenant = tenantMapper.toEntity(request);
        tenant.setSlug(slug);
        tenant.setMaxUsers(request.getPlan().getMaxUsers());
        tenant.setMaxWorkflows(request.getPlan().getMaxWorkflows());

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant: {} ({})", saved.getName(), saved.getId());

        TenantEvent event = TenantEvent.tenantCreated(
                saved.getId(), saved.getName(), saved.getPlan().name(), saved.getAdminEmail());
        kafkaTemplate.send(TENANT_EVENTS_TOPIC, saved.getId(), event);

        return tenantMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TenantResponse getTenantById(String id) {
        return tenantRepository.findById(id)
                .map(tenantMapper::toResponse)
                .orElseThrow(() -> ServiceException.notFound("Tenant", id));
    }

    @Transactional(readOnly = true)
    public TenantResponse getTenantBySlug(String slug) {
        return tenantRepository.findBySlug(slug)
                .map(tenantMapper::toResponse)
                .orElseThrow(() -> ServiceException.notFound("Tenant", slug));
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> getAllActiveTenants() {
        return tenantRepository.findAllByStatus(TenantStatus.ACTIVE)
                .stream()
                .map(tenantMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public TenantResponse suspendTenant(String id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> ServiceException.notFound("Tenant", id));

        if (!tenant.isActive()) {
            throw ServiceException.badRequest("Tenant is not active");
        }

        tenant.suspend();
        Tenant saved = tenantRepository.save(tenant);
        log.info("Suspended tenant: {}", id);

        kafkaTemplate.send(TENANT_EVENTS_TOPIC, id, TenantEvent.tenantSuspended(id));

        return tenantMapper.toResponse(saved);
    }

    @Transactional
    public TenantResponse activateTenant(String id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> ServiceException.notFound("Tenant", id));

        tenant.activate();
        Tenant saved = tenantRepository.save(tenant);
        log.info("Activated tenant: {}", id);

        return tenantMapper.toResponse(saved);
    }

    private String generateSlug(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD);
        String slug = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", "-");
        slug = NON_ALPHANUMERIC.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        return slug.replaceAll("^-|-$", "");
    }
}
