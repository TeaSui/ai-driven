package com.teasui.tenant.mapper;

import com.teasui.tenant.domain.Tenant;
import com.teasui.tenant.dto.CreateTenantRequest;
import com.teasui.tenant.dto.TenantResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface TenantMapper {

    TenantResponse toResponse(Tenant tenant);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "maxUsers", ignore = true)
    @Mapping(target = "maxWorkflows", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "suspendedAt", ignore = true)
    Tenant toEntity(CreateTenantRequest request);
}
