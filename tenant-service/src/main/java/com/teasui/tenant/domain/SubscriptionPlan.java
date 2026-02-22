package com.teasui.tenant.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubscriptionPlan {

    STARTER(5, 10, "Starter plan - up to 5 users and 10 workflows"),
    PROFESSIONAL(25, 100, "Professional plan - up to 25 users and 100 workflows"),
    ENTERPRISE(Integer.MAX_VALUE, Integer.MAX_VALUE, "Enterprise plan - unlimited users and workflows");

    private final int maxUsers;
    private final int maxWorkflows;
    private final String description;
}
