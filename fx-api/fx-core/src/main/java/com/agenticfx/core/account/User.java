package com.agenticfx.core.account;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record User(UUID id, String email, String displayName, Set<Role> roles) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public enum Role {
        /** End customer: owns goals, mandates, approvals. */
        CUSTOMER,
        /** Operator: read-only access to audit trails across users. */
        OPERATOR,
        /** The agent-service identity: may read state and create proposals, never approve or execute. */
        AGENT
    }
}
