package com.teasui.crm.common.event.auth;

import com.teasui.crm.common.event.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Event published when user authentication state changes.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserAuthEvent extends BaseEvent {

    public static final String EVENT_TYPE = "USER_AUTH";

    private String userId;
    private String username;
    private String email;
    private AuthAction action;
    private String ipAddress;
    private String userAgent;

    public enum AuthAction {
        LOGIN,
        LOGOUT,
        TOKEN_REFRESH,
        PASSWORD_CHANGED,
        ACCOUNT_LOCKED,
        ACCOUNT_UNLOCKED
    }
}
