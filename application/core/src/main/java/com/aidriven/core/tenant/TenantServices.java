package com.aidriven.core.tenant;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import com.aidriven.spi.TenantContext;
import com.aidriven.spi.event.EventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Holds the resolved set of services for a specific tenant.
 * Provides typed access to modules by category.
 */
@Slf4j
public class TenantServices {

    private final TenantContext context;
    private final List<ServiceDescriptor> descriptors;
    private final EventBus eventBus;
    private final Map<ServiceCategory, List<ServiceDescriptor>> byCategory;

    public TenantServices(TenantContext context, List<ServiceDescriptor> descriptors, EventBus eventBus) {
        this.context = Objects.requireNonNull(context);
        this.descriptors = List.copyOf(descriptors);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.byCategory = descriptors.stream()
                .collect(Collectors.groupingBy(ServiceDescriptor::category));

        log.info("TenantServices for '{}': {} modules across {} categories",
                context.tenantId(), descriptors.size(), byCategory.keySet());
    }

    public TenantContext context() {
        return context;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    /**
     * Returns all module descriptors for this tenant.
     */
    public List<ServiceDescriptor> allModules() {
        return descriptors;
    }

    /**
     * Returns module descriptors for a specific category.
     */
    public List<ServiceDescriptor> modulesForCategory(ServiceCategory category) {
        return byCategory.getOrDefault(category, List.of());
    }

    /**
     * Returns the first (primary) module for a category.
     * Useful when only one module per category is expected.
     */
    public Optional<ServiceDescriptor> primaryModule(ServiceCategory category) {
        List<ServiceDescriptor> modules = modulesForCategory(category);
        return modules.isEmpty() ? Optional.empty() : Optional.of(modules.get(0));
    }

    /**
     * Checks if a specific module is enabled for this tenant.
     */
    public boolean hasModule(String moduleId) {
        return descriptors.stream().anyMatch(d -> d.id().equals(moduleId));
    }

    /**
     * Returns the set of enabled module IDs.
     */
    public Set<String> enabledModuleIds() {
        return descriptors.stream().map(ServiceDescriptor::id).collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        return "TenantServices{tenant='" + context.tenantId()
                + "', modules=" + enabledModuleIds() + "}";
    }
}
